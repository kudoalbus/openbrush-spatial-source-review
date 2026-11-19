#!/usr/bin/env python3
"""Independently compare a simulator-saved v3 workspace with the shared input."""
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
original = json.loads((root / 'ReviewResources/workspace-v3.json').read_text())
saved = json.loads(Path(sys.argv[1]).read_text())
assert saved['version'] == 3 and saved['units'] == 'meters_rh'
assert len(saved['strokes']) == 1
before, after = original['strokes'][0], saved['strokes'][0]
for field in ['id', 'brush', 'width', 'color', 'points', 'erased']:
    assert after[field] == before[field], field
assert after['recolored'] == [
    [0.08, 0.10, 1, 0.12, 0.05, 1],
    [0.10, 0.12, 1, 0.55, 0.05, 1],
    [0.16, 0.18, 1, 0.55, 0.05, 1],
    [0.18, 0.20, 0.1, 0.85, 0.3, 1],
]
print('PASS: original fields unchanged; four expected color intervals; erased gap untouched')
