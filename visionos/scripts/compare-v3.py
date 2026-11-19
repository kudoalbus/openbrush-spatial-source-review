"""Compare two shared v3 workspaces, including order and exact timestamps."""
import json
import math
from pathlib import Path
import sys


def compare(expected, actual, path="document"):
    if isinstance(expected, dict):
        assert isinstance(actual, dict) and expected.keys() == actual.keys(), path
        for key, value in expected.items():
            compare(value, actual[key], f"{path}.{key}")
    elif isinstance(expected, list):
        assert isinstance(actual, list) and len(expected) == len(actual), path
        for index, (left, right) in enumerate(zip(expected, actual)):
            compare(left, right, f"{path}[{index}]")
    elif isinstance(expected, (int, float)) and not isinstance(expected, bool):
        assert isinstance(actual, (int, float)) and not isinstance(actual, bool), path
        if path == "document.version" or (".points[" in path and path.endswith("][4]")):
            assert isinstance(actual, int) and expected == actual, path
        else:
            assert math.isclose(expected, actual, rel_tol=1e-6, abs_tol=1e-6), path
    else:
        assert expected == actual, path


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: python3 compare-v3.py original.json returned.json")
    left, right = [json.loads(Path(name).read_text(encoding="utf-8-sig")) for name in sys.argv[1:]]
    assert left["version"] == 3 and left["units"] == "meters_rh"
    compare(left, right)
    print("v3 content and ordering match")
