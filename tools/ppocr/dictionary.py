#!/usr/bin/env python3
"""Verify the committed vocabulary is verbatim pinned ONNX metadata, no ONNX package."""
import pathlib
ROOT=pathlib.Path(__file__).resolve().parents[2]
def varint(data,pos):
    value=shift=0
    while pos<len(data) and shift<70:
        byte=data[pos];pos+=1;value|=(byte&127)<<shift
        if byte<128:return value,pos
        shift+=7
    raise ValueError('malformed protobuf varint')
def fields(data):
    pos=0
    while pos<len(data):
        tag,pos=varint(data,pos);number=tag>>3;wire=tag&7
        if wire==0:value,pos=varint(data,pos)
        elif wire in (1,5):
            length=8 if wire==1 else 4;value=data[pos:pos+length];pos+=length
        elif wire==2:
            length,pos=varint(data,pos);value=data[pos:pos+length];pos+=length
        else:raise ValueError('unsupported protobuf wire type')
        if pos>len(data):raise ValueError('truncated protobuf field')
        yield number,value
metadata=[]
for number,value in fields((ROOT/'.cache/ppocr/en_PP-OCRv5_rec_mobile.onnx').read_bytes()):
    if number==14:
        entry=dict(fields(value))
        if entry.get(1)==b'character':metadata.append(entry[2])
assert len(metadata)==1,'recognizer must have exactly one character metadata entry'
assert metadata[0]==(ROOT/'tools/ppocr/en-dictionary.txt').read_bytes(),'dictionary differs from pinned model'
assert len(metadata[0].decode().splitlines())+2==438,'blank + vocabulary + space class count'
assert (ROOT/'tools/ppocr/orientation-labels.txt').read_bytes()==b'0\n180\n','classifier class order'
print('verified embedded English dictionary and classifier class order')
