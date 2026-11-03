#!/usr/bin/env python3
"""Offline supply-chain contracts: corrupted fetches never replace cache bytes."""
import hashlib,os,pathlib,shutil,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
class FetchContracts(unittest.TestCase):
    def setUp(self):
        scratch=ROOT/'.cache/op1-tmp';scratch.mkdir(parents=True,exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=scratch,prefix='ppocr-fetch-');self.root=pathlib.Path(self.temp.name)
        for name in ['tools/ppocr','.cache/ppocr','bin']:(self.root/name).mkdir(parents=True,exist_ok=True)
        shutil.copy(ROOT/'tools/ppocr/fetch.clj',self.root/'tools/ppocr/fetch.clj')
        sha=hashlib.sha256(b'abc').hexdigest()
        (self.root/'tools/ppocr/assets.edn').write_text('{:assets [{:file "test.bin" :bytes 3 :sha256 "'+sha+'" :url "https://invalid.example/test"}]}')
        self.env=dict(os.environ,EMON_CS_SHARED_COMPILATION='false',PATH=str(self.root/'bin')+os.pathsep+os.environ['PATH'])
        self.dest=self.root/'.cache/ppocr/test.bin';self.curl('bad')
    def tearDown(self):self.temp.cleanup()
    def curl(self,content):
        fake=self.root/'bin/curl'
        fake.write_text('#!/bin/sh\nwhile [ "$#" -gt 0 ]; do\n  if [ "$1" = --output ]; then shift; dest=$1; fi\n  shift\ndone\nprintf '+content+' > "$dest"\n')
        fake.chmod(0o755)
    def run_fetch(self):
        return subprocess.run([shutil.which('bb'),'tools/ppocr/fetch.clj'],cwd=self.root,env=self.env,capture_output=True,text=True)
    def test_failed_download_preserves_existing_bytes_and_removes_temporary(self):
        self.dest.write_bytes(b'old');r=self.run_fetch();self.assertNotEqual(r.returncode,0)
        self.assertEqual(self.dest.read_bytes(),b'old');self.assertEqual(sorted(p.name for p in self.dest.parent.iterdir()),['test.bin'])
    def test_valid_cache_is_offline_and_successful_refresh_is_checked(self):
        self.dest.write_bytes(b'abc');self.assertEqual(self.run_fetch().returncode,0)
        self.dest.write_bytes(b'old');self.curl('abc');self.assertEqual(self.run_fetch().returncode,0);self.assertEqual(self.dest.read_bytes(),b'abc')
    def test_symlink_asset_is_refused(self):
        target=self.root/'outside';target.write_bytes(b'abc');self.dest.symlink_to(target)
        self.assertNotEqual(self.run_fetch().returncode,0);self.assertEqual(target.read_bytes(),b'abc')
if __name__=='__main__':unittest.main()
