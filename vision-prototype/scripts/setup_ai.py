"""
One-time setup for AI (DocRes): clone repo, create dirs for weights
Run from vision-prototype: python scripts/setup_ai.py
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

VISION_ROOT = Path(__file__).resolve().parent.parent
DEPS_DIR = VISION_ROOT / "deps"
DOCRES_REPO = DEPS_DIR / "DocRes"
DOCRES_URL = "https://github.com/ZZZHANG-jx/DocRes.git"


def main():
    DEPS_DIR.mkdir(parents=True, exist_ok=True)

    if not (DOCRES_REPO / ".git").exists():
        print("Cloning DocRes...")
        subprocess.run(
            ["git", "clone", DOCRES_URL, str(DOCRES_REPO)],
            cwd=str(VISION_ROOT),
            check=True,
        )
        print("Cloned to", DOCRES_REPO)
    else:
        print("DocRes already at", DOCRES_REPO)

    (DOCRES_REPO / "data" / "MBD" / "checkpoint").mkdir(parents=True, exist_ok=True)
    (DOCRES_REPO / "checkpoints").mkdir(parents=True, exist_ok=True)

    print()
    print("Download weights from: https://1drv.ms/f/s!Ak15mSdV3Wy4iahoKckhDPVP5e2Czw?e=iClwdK")
    print("  - mbd.pkl  ->", DOCRES_REPO / "data" / "MBD" / "checkpoint" / "mbd.pkl")
    print("  - docres.pkl ->", DOCRES_REPO / "checkpoints" / "docres.pkl")
    print()
    print("Then set DOCRES_DIR and run with --ai-enhance:")
    print(f"  set DOCRES_DIR={DOCRES_REPO}")
    print("  python scanner.py --input ... --output ... --ai-enhance")


if __name__ == "__main__":
    main()
