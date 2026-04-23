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

import socket
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

_MDNS_PORT = 8765
_zeroconf = None
_zc_info = None


def _local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


@app.on_event("startup")
async def _advertise_mdns() -> None:
    import asyncio
    import traceback
    global _zeroconf, _zc_info
    # Run in thread — zeroconf is blocking
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, _register_mdns)


def _register_mdns() -> None:
    global _zeroconf, _zc_info
    try:
        from zeroconf import ServiceInfo, Zeroconf
        ip = _local_ip()
        _zeroconf = Zeroconf()
        _zc_info = ServiceInfo(
            "_scanmeow._tcp.local.",
            "ScanMeow._scanmeow._tcp.local.",
            addresses=[socket.inet_aton(ip)],
            port=_MDNS_PORT,
            properties={"v": "1"},
        )
        _zeroconf.register_service(_zc_info)
        print(f"mDNS: ScanMeow advertised at {ip}:{_MDNS_PORT}", flush=True)
    except Exception:
        import traceback
        traceback.print_exc()
        print("mDNS advertise failed (non-fatal) — app will still work via direct IP", flush=True)


@app.on_event("shutdown")
def _unadvertise_mdns() -> None:
    if _zeroconf and _zc_info:
        try:
            _zeroconf.unregister_service(_zc_info)
            _zeroconf.close()
        except Exception:
            pass


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
        bg = cv2.GaussianBlur(processed, (71, 71), 0)
        processed = cv2.divide(processed, np.maximum(bg, 1), scale=255)
        clahe = cv2.createCLAHE(clipLimit=1.5, tileGridSize=(8, 8))
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


@app.post("/detect")
async def detect_endpoint(file: UploadFile = File(...)):
    """Detect document corners; returns corners in pixel coords + image dims."""
    body = await file.read()
    nparr = np.frombuffer(body, np.uint8)
    image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    if image is None:
        raise HTTPException(status_code=400, detail="Could not decode image")
    h, w = image.shape[:2]
    corners = sc.detect_corners(image)
    if corners is None:
        inset = 0.05
        corners = np.array([
            [w * inset,       h * inset      ],
            [w * (1 - inset), h * inset      ],
            [w * (1 - inset), h * (1 - inset)],
            [w * inset,       h * (1 - inset)],
        ], dtype="float32")
    return {"corners": corners.tolist(), "width": w, "height": h}


@app.post("/warp")
async def warp_endpoint(
    file: UploadFile = File(...),
    tl_x: float = 0, tl_y: float = 0,
    tr_x: float = 0, tr_y: float = 0,
    br_x: float = 0, br_y: float = 0,
    bl_x: float = 0, bl_y: float = 0,
    binarize: bool = True,
    upright: bool = True,
    whiten: bool = True,
    sharpen: float = 0.55,
):
    """Warp image using provided corners and apply document processing."""
    import time
    t0 = time.time()

    body = await file.read()
    nparr = np.frombuffer(body, np.uint8)
    image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    if image is None:
        raise HTTPException(status_code=400, detail="Could not decode image")
    print(f"[warp] decode:  {time.time()-t0:.2f}s  input={image.shape}", flush=True)

    corners = np.array(
        [[tl_x, tl_y], [tr_x, tr_y], [br_x, br_y], [bl_x, bl_y]], dtype="float32"
    )
    h, w = image.shape[:2]
    corners[:, 0] = np.clip(corners[:, 0], 0, w - 1)
    corners[:, 1] = np.clip(corners[:, 1], 0, h - 1)

    t1 = time.time()
    aligned = sc.four_point_transform(image, corners)
    print(f"[warp] warp:    {time.time()-t1:.2f}s  warped={aligned.shape}", flush=True)

    # Cap output to 1600px max so all subsequent ops stay fast
    t1 = time.time()
    max_dim = max(aligned.shape[:2])
    if max_dim > 1600:
        scale = 1600 / max_dim
        aligned = cv2.resize(aligned, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    print(f"[warp] resize:  {time.time()-t1:.2f}s  out={aligned.shape}", flush=True)

    if upright:
        aligned = _upright_reading_order(aligned)

    if binarize:
        t1 = time.time()
        processed = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY) if len(aligned.shape) == 3 else aligned.copy()
        # Background normalization: large Gaussian estimates the illumination surface,
        # dividing removes shadows and gradients without slow morphological ops
        bg = cv2.GaussianBlur(processed, (71, 71), 0)
        processed = cv2.divide(processed, np.maximum(bg, 1), scale=255)
        print(f"[warp] bg_norm:  {time.time()-t1:.2f}s", flush=True)

        t1 = time.time()
        clahe = cv2.createCLAHE(clipLimit=1.5, tileGridSize=(8, 8))
        processed = clahe.apply(processed)
        if sharpen > 0:
            blurred = cv2.GaussianBlur(processed, (0, 0), 0.9)
            processed = np.clip(
                processed.astype(np.float32) + sharpen * (processed.astype(np.float32) - blurred.astype(np.float32)),
                0, 255,
            ).astype(np.uint8)
        print(f"[warp] clahe+sharpen: {time.time()-t1:.2f}s", flush=True)
    else:
        processed = aligned

    t1 = time.time()
    ok, buf = cv2.imencode(".jpg", processed, [cv2.IMWRITE_JPEG_QUALITY, 85])
    if not ok:
        raise HTTPException(status_code=500, detail="cv2.imencode failed")
    print(f"[warp] encode:  {time.time()-t1:.2f}s  total={time.time()-t0:.2f}s", flush=True)
    return Response(content=buf.tobytes(), media_type="image/jpeg")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8765)
