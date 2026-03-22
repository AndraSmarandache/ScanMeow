"""
Optional AI (DocRes): runs deshadowing + appearance when DOCRES_DIR is set
Returns image unchanged if not configured or inference fails
"""
import os
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np


def run_ai_shadow_removal(image_bgr: np.ndarray) -> np.ndarray:
    """
    If DOCRES_DIR is set, run DocRes deshadowing and return result
    """
    docres_dir = os.environ.get("DOCRES_DIR", "").strip()
    if not docres_dir or not os.path.isdir(docres_dir):
        print("[INFO] AI skipped: set DOCRES_DIR (see README)", file=sys.stderr)
        return image_bgr
    docres_ckpt = Path(docres_dir) / "checkpoints" / "docres.pkl"
    if not docres_ckpt.is_file():
        print("[INFO] AI skipped: checkpoints/docres.pkl not found (download from DocRes README)", file=sys.stderr)
        return image_bgr

    import tempfile
    with tempfile.TemporaryDirectory(prefix="scanmeow_docres_") as tmp_dir:
        tmp_dir = Path(tmp_dir)
        in_path = tmp_dir / "in.jpg"
        out_path = tmp_dir / "out.jpg"
        cv2.imwrite(str(in_path), image_bgr)

        wrapper = Path(__file__).resolve().parent.parent / "scripts" / "docres_inference_single.py"
        if not wrapper.is_file():
            print("[WARN] DocRes wrapper not found", file=sys.stderr)
            return image_bgr

        cmd = [
            sys.executable,
            str(wrapper),
            "--docres-dir",
            str(Path(docres_dir)),
            "--input",
            str(in_path),
            "--output",
            str(out_path),
            "--max-size",
            "1024",
        ]
        print("[INFO] Running DocRes (deshadowing)...", file=sys.stderr)
        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=600,
                cwd=str(Path(__file__).resolve().parent.parent),
            )
            if result.returncode != 0:
                print(f"[WARN] DocRes failed: {result.stderr or result.stdout}", file=sys.stderr)
                return image_bgr
            if not out_path.is_file():
                return image_bgr
            out = cv2.imread(str(out_path))
            if out is not None:
                print("[INFO] DocRes done.", file=sys.stderr)
                return out
        except (subprocess.TimeoutExpired, FileNotFoundError, Exception) as e:
            print(f"[WARN] DocRes failed: {e}", file=sys.stderr)
    return image_bgr
