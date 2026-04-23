import argparse
from pathlib import Path

import cv2
import numpy as np

try:
    from ai.shadow_removal import run_ai_shadow_removal
except ImportError:
    run_ai_shadow_removal = None


def order_points(pts: np.ndarray) -> np.ndarray:
    """Order 4 corner points as top-left, top-right, bottom-right, bottom-left (for perspective transform)"""
    rect = np.zeros((4, 2), dtype="float32")
    pts = np.array(pts)
    s = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)]   # top-left has smallest sum
    rect[2] = pts[np.argmax(s)]   # bottom-right has largest sum
    diff = np.diff(pts, axis=1)
    rect[1] = pts[np.argmin(diff)]
    rect[3] = pts[np.argmax(diff)]
    return rect


def find_dest(pts: np.ndarray):
    """Destination rectangle corners from ordered pts: (0,0), (W,0), (W,H), (0,H) using max width/height"""
    (tl, tr, br, bl) = pts
    width_a = np.sqrt(((br[0] - bl[0]) ** 2) + ((br[1] - bl[1]) ** 2))
    width_b = np.sqrt(((tr[0] - tl[0]) ** 2) + ((tr[1] - tl[1]) ** 2))
    max_width = max(int(width_a), int(width_b))
    height_a = np.sqrt(((tr[0] - br[0]) ** 2) + ((tr[1] - br[1]) ** 2))
    height_b = np.sqrt(((tl[0] - bl[0]) ** 2) + ((tl[1] - bl[1]) ** 2))
    max_height = max(int(height_a), int(height_b))
    return [[0, 0], [max_width, 0], [max_width, max_height], [0, max_height]]


def four_point_transform(image: np.ndarray, pts: np.ndarray) -> np.ndarray:
    """Warp image so the quad defined by pts becomes an axis-aligned rectangle"""
    rect = order_points(pts)
    dest = np.array(find_dest(rect), dtype="float32")
    M = cv2.getPerspectiveTransform(rect, dest)
    w, h = int(dest[2][0]), int(dest[2][1])
    return cv2.warpPerspective(image, M, (w, h), flags=cv2.INTER_LINEAR)


def paper_whiten(gray: np.ndarray, kernel_size: int = 51, max_gain: float = 0.95, blend: float = 0.6) -> np.ndarray:
    """Mild background flattening to reduce paper bleed / show-through, avoid shadow at edges"""
    if len(gray.shape) == 3:
        gray = cv2.cvtColor(gray, cv2.COLOR_BGR2GRAY)
    gray_f = np.clip(gray.astype(np.float32), 1, 255)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kernel_size, kernel_size))
    background = cv2.morphologyEx(gray.astype(np.uint8), cv2.MORPH_OPEN, kernel)
    background = np.maximum(background.astype(np.float32), 1)
    gain = np.minimum(255.0 / background, max_gain)
    whitened = np.clip(gray_f * gain, 0, 255).astype(np.float32)
    out = np.clip(blend * whitened + (1 - blend) * gray_f, 0, 255).astype(np.uint8)
    return out


def _find_quad(edges: np.ndarray, img_area: float, scale: float, min_area: float = 0.08):
    """Find best 4-point convex quadrilateral from an edge map."""
    contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    for c in sorted(contours, key=cv2.contourArea, reverse=True)[:20]:
        if cv2.contourArea(c) < img_area * min_area:
            break
        # Convex hull first: stabilises noisy/concave document outlines
        hull = cv2.convexHull(c)
        arc = cv2.arcLength(hull, True)
        for eps in (0.01, 0.02, 0.03, 0.04, 0.05, 0.07, 0.10):
            approx = cv2.approxPolyDP(hull, eps * arc, True)
            if len(approx) == 4 and cv2.isContourConvex(approx):
                pts = approx.reshape(4, 2).astype("float32") / scale
                return order_points(pts)
    return None


def detect_corners(img: np.ndarray):
    """Detect document quad corners. Returns ordered (TL, TR, BR, BL) or None.

    Uses bilateral filter to preserve sharp document edges while suppressing
    paper texture, then tries three edge maps in order of reliability.
    """
    dim_limit = 800
    max_dim = max(img.shape[:2])
    scale = 1.0
    if max_dim > dim_limit:
        scale = dim_limit / max_dim
        small = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    else:
        small = img.copy()

    h, w = small.shape[:2]
    img_area = h * w

    # Bilateral filter: blurs paper texture while keeping document boundary edges sharp
    filtered = cv2.bilateralFilter(small, 9, 75, 75)
    gray = cv2.cvtColor(filtered, cv2.COLOR_BGR2GRAY)

    # Edge map 1: Otsu-derived thresholds — adapts to scene brightness automatically
    otsu_val, _ = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    otsu_val = max(otsu_val, 1)
    edges1 = cv2.Canny(gray, otsu_val * 0.5, otsu_val)
    edges1 = cv2.dilate(edges1, cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)))

    # Edge map 2: fixed moderate thresholds — reliable for well-lit docs on contrasting surface
    edges2 = cv2.Canny(gray, 30, 100)
    edges2 = cv2.dilate(edges2, cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)))

    # Edge map 3: histogram-equalized — catches low-contrast or overexposed documents
    eq = cv2.equalizeHist(gray)
    edges3 = cv2.Canny(eq, 50, 150)
    edges3 = cv2.dilate(edges3, cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)))

    for edges in (edges1, edges2, edges3):
        result = _find_quad(edges, img_area, scale)
        if result is not None:
            return result

    return None


def scan(img: np.ndarray):
    """
    Detect document quad and return warped original (aligned full page), or None if no 4-corner contour
    Steps: resize -> morphology (blank page) -> GrabCut (no background) -> Canny -> contours -> 4 corners -> warp orig
    """
    dim_limit = 1080
    max_dim = max(img.shape)
    if max_dim > dim_limit:
        scale = dim_limit / max_dim
        img = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)

    orig_img = img.copy()

    # Morphology: close to suppress text so edges are document boundary only
    kernel = np.ones((5, 5), np.uint8)
    img = cv2.morphologyEx(img, cv2.MORPH_CLOSE, kernel, iterations=3)

    # GrabCut: rect = full image minus 20px border as background hint; 5 iterations
    mask = np.zeros(img.shape[:2], np.uint8)
    bgd_model = np.zeros((1, 65), np.float64)
    fgd_model = np.zeros((1, 65), np.float64)
    h, w = img.shape[:2]
    rect = (20, 20, w - 20, h - 20)
    cv2.grabCut(img, mask, rect, bgd_model, fgd_model, 5, cv2.GC_INIT_WITH_RECT)
    mask2 = np.where((mask == 2) | (mask == 0), 0, 1).astype("uint8")
    img = img * mask2[:, :, np.newaxis]

    # Edge detection on GrabCut result; dilate to get thin closed outline
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (11, 11), 0)
    canny = cv2.Canny(gray, 0, 200)
    canny = cv2.dilate(canny, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))

    # Contours: take top 5 by area, first one that approximates to 4 points is the document quad
    contours, _ = cv2.findContours(canny, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
    page = sorted(contours, key=cv2.contourArea, reverse=True)[:5]

    corners = None
    for c in page:
        epsilon = 0.02 * cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, epsilon, True)
        if len(approx) == 4:
            corners = np.concatenate(approx).reshape(4, 2).astype("float32")
            break

    if corners is None:
        return None

    corners = order_points(corners)
    aligned = four_point_transform(orig_img, corners)
    return aligned


def process_image(
    path: Path,
    output_dir: Path,
    binarize: bool = True,
    ai_enhance: bool = False,
    whiten: bool = True,
    sharpen: float = 0.55,
) -> None:
    image = cv2.imread(str(path))
    if image is None:
        print(f"[WARN] Could not read image: {path}")
        return

    aligned = scan(image)
    if aligned is None:
        print(f"[WARN] No document contour (4 corners) found in: {path.name}")
        # Fallback: resize and grayscale only
        dim_limit = 1080
        max_dim = max(image.shape)
        if max_dim > dim_limit:
            scale = dim_limit / max_dim
            image = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        aligned = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

    # Optional AI shadow removal before enhance; only for BGR aligned
    if ai_enhance and run_ai_shadow_removal is not None and len(aligned.shape) == 3:
        aligned = run_ai_shadow_removal(aligned)

    output_dir.mkdir(parents=True, exist_ok=True)

    if binarize:
        # Grayscale if needed
        processed = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY) if len(aligned.shape) == 3 else aligned.copy()
        # Paper whitening: flatten background, reduce show-through and shadows
        if whiten:
            processed = paper_whiten(processed, kernel_size=51)
        # Contrast + brightness: linear stretch for scanner-style pop
        processed = np.clip(processed.astype(np.float32) * 1.1 + 18, 0, 255).astype(np.uint8)
        # Local contrast (CLAHE): text and lines pop without global over-enhance
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
        processed = clahe.apply(processed)
        # Sharpen: unsharp mask (sigma 0.9, amount from --sharpen)
        if sharpen > 0:
            blurred = cv2.GaussianBlur(processed, (0, 0), 0.9)
            processed = np.clip(
                processed.astype(np.float32) + sharpen * (processed.astype(np.float32) - blurred.astype(np.float32)),
                0, 255,
            ).astype(np.uint8)
        out_path = output_dir / f"{path.stem}_scanned.jpg"
        cv2.imwrite(str(out_path), processed, [cv2.IMWRITE_JPEG_QUALITY, 95])
    else:
        processed = aligned
        out_path = output_dir / f"{path.stem}_scanned.jpg"
        cv2.imwrite(str(out_path), processed)

    # Save aligned color version when we have BGR input
    if len(aligned.shape) == 3:
        color_path = output_dir / f"{path.stem}_aligned.jpg"
        cv2.imwrite(str(color_path), aligned)

    print(f"[OK] Saved: {out_path}")


def main():
    parser = argparse.ArgumentParser(description="Document scanner: detect doc quad, warp, optional grayscale + enhance")
    parser.add_argument("--input", type=str, required=True, help="Input image or directory")
    parser.add_argument("--output", type=str, required=True, help="Output directory")
    parser.add_argument("--no-binarize", action="store_true", help="Save only aligned color, skip grayscale + enhance")
    parser.add_argument("--ai-enhance", action="store_true", help="Run DocRes deshadowing (set DOCRES_DIR)")
    parser.add_argument("--no-whiten", action="store_true", help="Disable paper whitening")
    parser.add_argument("--sharpen", type=float, default=0.55, metavar="N", help="Unsharp mask amount (0=off, 0.55=default)")
    args = parser.parse_args()
    input_path = Path(args.input)
    output_dir = Path(args.output)
    binarize = not args.no_binarize
    ai_enhance = args.ai_enhance
    whiten = not args.no_whiten
    sharpen = max(0.0, min(1.5, args.sharpen))

    if input_path.is_dir():
        images = sorted(
            p for p in input_path.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"}
        )
        if not images:
            print("[INFO] No images in directory")
            return
        for p in images:
            process_image(p, output_dir, binarize=binarize, ai_enhance=ai_enhance, whiten=whiten, sharpen=sharpen)
    else:
        process_image(input_path, output_dir, binarize=binarize, ai_enhance=ai_enhance, whiten=whiten, sharpen=sharpen)


if __name__ == "__main__":
    main()
