#!/usr/bin/env python3
"""REFERENCE PREDICTOR "oracle": gold -> Model. Scores perfect, by construction.

A predictor is a command run as  CMD ID VARIANT INPUT GOLD  (variant: pdf, scan,
pdf-tagged; INPUT the committed file; GOLD the gold file) that prints an emon-1
Model as JSON on stdout. Exit 3 means "not available yet" (not a failure).

This one ignores INPUT and copies the gold's model. It exists to prove the
scorer and the ratchet: if it is not perfect, the SCORER is wrong (D074).
"""
import json
import sys

with open(sys.argv[4]) as f:
    json.dump(json.load(f)["model"], sys.stdout)
