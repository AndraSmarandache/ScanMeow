# Vision prototype: document scanner

OpenCV-based document detection (morphology, GrabCut, perspective warp) plus optional grayscale enhance. Optional AI step for shadow removal and appearance (DocRes).

## Quick run

**From repo root (ScanMeow):**
```bash
pip install -r vision-prototype/requirements.txt
python run_scanner.py --input vision-prototype/sample-images/input.jpeg --output vision-prototype/scanned-output
```

**From vision-prototype folder:**
```bash
cd vision-prototype
pip install -r requirements.txt
python scanner.py --input sample-images/input.jpeg --output scanned-output
```

- `scanned-output/<name>_scanned.jpg` — grayscale + contrast/CLAHE/sharpen
- `scanned-output/<name>_aligned.jpg` — aligned color (no enhance)

## Options

| Flag | Effect |
|------|--------|
| `--no-binarize` | Save only aligned color, skip grayscale + enhance |
| `--ai-enhance` | Run DocRes (deshadowing + appearance) before enhance (needs DOCRES_DIR) |
| `--no-whiten` | Disable paper whitening |
| `--sharpen N` | Unsharp mask amount (0=off, 0.4=default) |

## AI (DocRes)

One-time setup:

1. Clone DocRes and install deps:
```bash
cd vision-prototype
python scripts/setup_ai.py
```

2. Download weights from [DocRes OneDrive](https://1drv.ms/f/s!Ak15mSdV3Wy4iahoKckhDPVP5e2Czw?e=iClwdK):
   - `mbd.pkl` → `deps/DocRes/data/MBD/checkpoint/`
   - `docres.pkl` → `deps/DocRes/checkpoints/`

3. Run with AI (from repo root; DOCRES_DIR is set automatically by run_scanner.py):
```bash
python run_scanner.py --input vision-prototype/sample-images/input.jpeg --output vision-prototype/scanned-output --ai-enhance
```

Or from vision-prototype with env set:
**Windows:** `set DOCRES_DIR=...\vision-prototype\deps\DocRes` then `python scanner.py ... --ai-enhance`
**Linux/macOS:** `export DOCRES_DIR="$(pwd)/deps/DocRes"` then `python scanner.py ... --ai-enhance`

If `DOCRES_DIR` is not set or inference fails, the pipeline runs without AI.
