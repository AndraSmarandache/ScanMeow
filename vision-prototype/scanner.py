import argparse
from pathlib import Path

import cv2
import numpy as np


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


def process_image(path: Path, output_dir: Path, binarize: bool = True) -> None:
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

    output_dir.mkdir(parents=True, exist_ok=True)

    if binarize and len(aligned.shape) == 3:
        # Grayscale (luminance only)
        processed = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)

        # Denoise: bilateral keeps edges, smooths flat regions; d=5, sigma 25 = mild
        processed = cv2.bilateralFilter(processed, d=5, sigmaColor=25, sigmaSpace=25)

        # Contrast + brightness: linear stretch for scanner-style pop
        processed = np.clip(processed.astype(np.float32) * 1.18 + 8, 0, 255).astype(np.uint8)

        # Local contrast (CLAHE): text and lines pop without global over-enhance
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
        processed = clahe.apply(processed)

        # Sharpen: unsharp mask with amount 0.4 for crisper text
        blurred = cv2.GaussianBlur(processed, (0, 0), 1.2)
        processed = np.clip(
            processed.astype(np.float32) + 0.4 * (processed.astype(np.float32) - blurred.astype(np.float32)),
            0, 255,
        ).astype(np.uint8)

        out_path = output_dir / f"{path.stem}_scanned.jpg"
        cv2.imwrite(str(out_path), processed)
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
    parser = argparse.ArgumentParser(description="Document scanner: detect doc quad, warp, optional grayscale + enhance.")
    parser.add_argument("--input", type=str, required=True, help="Input image or directory.")
    parser.add_argument("--output", type=str, required=True, help="Output directory.")
    parser.add_argument("--no-binarize", action="store_true", help="Save only aligned color, skip grayscale + enhance.")
    args = parser.parse_args()
    input_path = Path(args.input)
    output_dir = Path(args.output)
    binarize = not args.no_binarize

    if input_path.is_dir():
        images = sorted(
            p for p in input_path.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"}
        )
        if not images:
            print("[INFO] No images in directory.")
            return
        for p in images:
            process_image(p, output_dir, binarize=binarize)
    else:
        process_image(input_path, output_dir, binarize=binarize)


if __name__ == "__main__":
    main()
