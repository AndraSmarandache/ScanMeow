"""
Run the vision-prototype scanner from repo root
"""
import os
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent
VISION = REPO_ROOT / "vision-prototype"
DOCRES_DIR = VISION / "deps" / "DocRes"

if not VISION.is_dir():
    print("vision-prototype/ not found. Run from ScanMeow repo root.", file=sys.stderr)
    sys.exit(1)

os.environ["DOCRES_DIR"] = str(DOCRES_DIR)
sys.path.insert(0, str(VISION))

# Run scanner (paths in args are relative to current dir = repo root)
import scanner
scanner.main()
