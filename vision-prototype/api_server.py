"""
Local HTTP API for the Android app (or any client) to run the same pipeline as scanner.py.

Run from the ``vision-prototype`` folder (Python module names cannot include ``-``):

    cd vision-prototype
    pip install -r requirements.txt -r requirements-api.txt
    uvicorn api_server:app --host 0.0.0.0 --port 8765

Emulator Android: POST to http://10.0.2.2:8765/scan
Physical device on same Wi‑Fi: use your PC's LAN IP, e.g. http://192.168.1.x:8765/scan
"""

from __future__ import annotations

import cv2
import numpy as np
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response

import scanner as sc

app = FastAPI(title="ScanMeow vision-prototype")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


def _upright_reading_order(img: np.ndarray) -> np.ndarray:
    """Prefer portrait reading (text top→bottom, lines left→right): if page is wider than tall, rotate 90° CW."""
    if img.ndim == 2:
        h, w = img.shape[:2]
    else:
        h, w = img.shape[:2]
    if w > h:
        return cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
    return img


def _process_uploaded_bgr(
    image: np.ndarray,
    *,
    binarize: bool,
    ai_enhance: bool,
    whiten: bool,
    sharpen: float,
    upright: bool,
) -> np.ndarray:
    # AI enhancement is allowed only for final scanned output
    ai_enhance = ai_enhance and binarize
    aligned = sc.scan(image)
    if aligned is None:
        dim_limit = 1080
        max_dim = max(image.shape)
        if max_dim > dim_limit:
            scale = dim_limit / max_dim
            image = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        # For aligned preview we want only alignment / rotation, with no tonal changes
        # If we fail to detect the document quad, fall back to the original color image (optionally resized)
        aligned = image

    if ai_enhance and sc.run_ai_shadow_removal is not None and len(aligned.shape) == 3:
        aligned = sc.run_ai_shadow_removal(aligned)

    if upright:
        aligned = _upright_reading_order(aligned)

    if binarize:
        processed = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY) if len(aligned.shape) == 3 else aligned.copy()
        if whiten:
            processed = sc.paper_whiten(processed, kernel_size=51)
        processed = np.clip(processed.astype(np.float32) * 1.1 + 18, 0, 255).astype(np.uint8)
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
        processed = clahe.apply(processed)
        if sharpen > 0:
            blurred = cv2.GaussianBlur(processed, (0, 0), 0.9)
            processed = np.clip(
                processed.astype(np.float32) + sharpen * (processed.astype(np.float32) - blurred.astype(np.float32)),
                0,
                255,
            ).astype(np.uint8)
        return processed

    return aligned


@app.get("/health")
def health():
    return {"ok": True}


@app.post("/scan")
async def scan_endpoint(
    file: UploadFile = File(...),
    # Same as demo_document_aligned.jpg from CLI (color warp). Set true for *_scanned.jpg style.
    binarize: bool = False,
    upright: bool = False,
    ai_enhance: bool = False,
    whiten: bool = True,
    sharpen: float = 0.55,
):
    body = await file.read()
    if not body:
        raise HTTPException(status_code=400, detail="Empty body")

    nparr = np.frombuffer(body, np.uint8)
    image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    if image is None:
        raise HTTPException(status_code=400, detail="Could not decode image (need JPEG/PNG bytes)")

    try:
        out = _process_uploaded_bgr(
            image,
            binarize=binarize,
            upright=upright,
            ai_enhance=ai_enhance,
            whiten=whiten,
            sharpen=max(0.0, min(1.5, sharpen)),
        )
    except Exception as e:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=str(e)) from e

    ok, buf = cv2.imencode(".jpg", out, [cv2.IMWRITE_JPEG_QUALITY, 95])
    if not ok:
        raise HTTPException(status_code=500, detail="cv2.imencode failed")

    return Response(content=buf.tobytes(), media_type="image/jpeg")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8765)
