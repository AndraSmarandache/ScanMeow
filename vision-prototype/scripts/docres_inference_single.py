"""
DocRes single-image inference wrapper optimized for CPU
Runs deshadowing, writes output to the provided output path
Usage: python docres_inference_single.py --docres-dir <path> --input <in.jpg> --output <out.jpg>
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np
import torch


def stride_integral(im: np.ndarray, stride: int = 8):
    h, w = im.shape[:2]
    pad_h = (stride - (h % stride)) % stride
    pad_w = (stride - (w % stride)) % stride
    padded = cv2.copyMakeBorder(im, pad_h, 0, pad_w, 0, borderType=cv2.BORDER_CONSTANT, value=0)
    return padded, pad_h, pad_w


def deshadow_prompt(img_bgr: np.ndarray) -> np.ndarray:
    h, w = img_bgr.shape[:2]
    img = cv2.resize(img_bgr, (1024, 1024))
    rgb_planes = cv2.split(img)
    result_norm_planes = []
    bg_imgs = []
    for plane in rgb_planes:
        dilated_img = cv2.dilate(plane, np.ones((7, 7), np.uint8))
        bg_img = cv2.medianBlur(dilated_img, 21)
        bg_imgs.append(bg_img)
        diff_img = 255 - cv2.absdiff(plane, bg_img)
        norm_img = cv2.normalize(diff_img, None, alpha=0, beta=255, norm_type=cv2.NORM_MINMAX, dtype=cv2.CV_8UC1)
        result_norm_planes.append(norm_img)
    bg_imgs = cv2.merge(bg_imgs)
    bg_imgs = cv2.resize(bg_imgs, (w, h))
    return bg_imgs


def load_docres_model(docres_dir: Path, device: torch.device):
    sys.path.insert(0, str(docres_dir))
    from utils import convert_state_dict
    from models import restormer_arch

    model = restormer_arch.Restormer(
        inp_channels=6,
        out_channels=3,
        dim=48,
        num_blocks=[2, 3, 3, 4],
        num_refinement_blocks=4,
        heads=[1, 2, 4, 8],
        ffn_expansion_factor=2.66,
        bias=False,
        LayerNorm_type="WithBias",
        dual_pixel_task=True,
    )

    ckpt = docres_dir / "checkpoints" / "docres.pkl"
    state = convert_state_dict(torch.load(ckpt, map_location="cpu")["model_state"])
    model.load_state_dict(state, strict=True)
    model.eval()
    model.to(device)
    return model


def run_deshadowing(model, image_bgr: np.ndarray, device: torch.device, max_size: int = 1024) -> np.ndarray:
    im_org = image_bgr
    h, w = im_org.shape[:2]
    prompt = deshadow_prompt(im_org)
    in_im = np.concatenate((im_org, prompt), -1)

    if max(w, h) < max_size:
        in_im, padding_h, padding_w = stride_integral(in_im, 8)
    else:
        in_im = cv2.resize(in_im, (max_size, max_size))
        padding_h, padding_w = 0, 0

    in_im = (in_im / 255.0).astype(np.float32)
    in_im_t = torch.from_numpy(in_im.transpose(2, 0, 1)).unsqueeze(0).to(device)

    # CPU-friendly: keep float32 (DocRes uses half for GPU speed)
    with torch.no_grad():
        pred = model(in_im_t.float())
        pred = torch.clamp(pred, 0, 1)
        pred = pred[0].permute(1, 2, 0).cpu().numpy()
        pred = (pred * 255).astype(np.uint8)

    if max(w, h) < max_size:
        out_im = pred[padding_h:, padding_w:]
    else:
        pred[pred == 0] = 1
        shadow_map = cv2.resize(im_org, (max_size, max_size)).astype(float) / pred.astype(float)
        shadow_map = cv2.resize(shadow_map, (w, h))
        shadow_map[shadow_map == 0] = 0.00001
        out_im = np.clip(im_org.astype(float) / shadow_map, 0, 255).astype(np.uint8)

    return out_im


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--docres-dir", type=str, required=True)
    ap.add_argument("--input", type=str, required=True)
    ap.add_argument("--output", type=str, required=True)
    ap.add_argument("--max-size", type=int, default=1024)
    args = ap.parse_args()

    docres_dir = Path(args.docres_dir)
    in_path = Path(args.input)
    out_path = Path(args.output)

    if not (docres_dir / "checkpoints" / "docres.pkl").is_file():
        print("docres.pkl not found in checkpoints/", file=sys.stderr)
        sys.exit(2)

    image = cv2.imread(str(in_path))
    if image is None:
        print("Could not read input image", file=sys.stderr)
        sys.exit(2)

    device = torch.device("cpu")
    model = load_docres_model(docres_dir, device)
    out = run_deshadowing(model, image, device, max_size=int(args.max_size))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(out_path), out)


if __name__ == "__main__":
    main()

