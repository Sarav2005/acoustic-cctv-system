"""
Module 4 - ANPR: text extraction from a cropped plate image.

NOTE ON ENGINE CHOICE: originally scoped for EasyOCR, but EasyOCR pulls in
a full GPU-oriented PyTorch install (~3GB of CUDA wheels) which is overkill
for reading short alphanumeric strings off a plate crop and blew the disk
budget on the dev machine. Switched to Tesseract (via pytesseract) instead:
it's a few MB, has no model-download step (good for a from-scratch
ESP32-adjacent deployment box), and its accuracy on cropped, upright,
high-contrast plate text is comparable to EasyOCR for this use case. If
real-world accuracy turns out to need it later, EasyOCR is a drop-in swap
behind the same extract_plate_text() interface.

Pipeline per candidate crop:
  1. Upscale small crops (OCR engines do much better above ~300px wide)
  2. Grayscale + adaptive threshold to get clean black-text-on-white
  3. Tesseract OCR restricted to a plate-friendly character whitelist
     (A-Z, 0-9, space) and PSM 7/8 (treat as a single text line/word)
  4. Clean the raw string: uppercase, strip non-alphanumeric, collapse
     whitespace
"""

import re

import cv2
import numpy as np
import pytesseract

pytesseract.pytesseract.tesseract_cmd = r"D:\Tesseract\tesseract.exe"
# Indian plate format is roughly: 2 letters (state) + 2 digits (RTO code) +
# 1-2 letters (series) + 4 digits, e.g. "TN22AB1234". We don't hard-enforce
# this (plates vary, and enforcing too strictly just throws away partial
# reads), but we use it to sanity-check / prefer OCR results that look
# plate-shaped when multiple candidates are tried.
PLATE_PATTERN = re.compile(r"^[A-Z]{2}[0-9]{1,2}[A-Z]{0,3}[0-9]{3,4}$")

TESSERACT_CONFIG = (
    "--psm 7 --oem 3 "
    "-c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
)


def _preprocess_for_ocr(crop):
    """Turn a raw BGR plate crop into a clean binary image for OCR."""
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)

    # Upscale small crops - tesseract wants text at a reasonable pixel size.
    h, w = gray.shape[:2]
    target_w = 400
    if w < target_w:
        scale = target_w / float(w)
        gray = cv2.resize(gray, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_CUBIC)

    gray = cv2.bilateralFilter(gray, 9, 75, 75)

    # Adaptive threshold handles uneven lighting across the plate better
    # than a single global threshold.
    thresh = cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 31, 15
    )

    # Otsu as a fallback/alternative view - tried separately, not merged,
    # since adaptive vs otsu wins depend on the specific lighting.
    _, otsu = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)

    return [thresh, otsu]


def _clean_text(raw):
    text = raw.upper()
    text = re.sub(r"[^A-Z0-9]", "", text)
    return text


def extract_plate_text(crop, min_length=4):
    """
    Run OCR on a cropped plate image (BGR np.ndarray).

    Tries a couple of preprocessing variants and picks the best-looking
    result (prefers a string matching PLATE_PATTERN; otherwise falls back
    to the longest clean alphanumeric read).

    Returns: {"text": str, "matched_pattern": bool, "raw_candidates": [str]}
    or None if nothing usable was read.
    """
    if crop is None or crop.size == 0:
        return None

    variants = _preprocess_for_ocr(crop)
    raw_candidates = []

    for variant in variants:
        try:
            raw = pytesseract.image_to_string(variant, config=TESSERACT_CONFIG)
        except pytesseract.TesseractNotFoundError:
            raise
        except Exception:
            continue
        cleaned = _clean_text(raw)
        if len(cleaned) >= min_length:
            raw_candidates.append(cleaned)

    if not raw_candidates:
        return None

    # Prefer a candidate that matches the expected plate shape.
    for cand in raw_candidates:
        if PLATE_PATTERN.match(cand):
            return {"text": cand, "matched_pattern": True, "raw_candidates": raw_candidates}

    # Otherwise return the longest read (usually the most complete one).
    best = max(raw_candidates, key=len)
    return {"text": best, "matched_pattern": False, "raw_candidates": raw_candidates}


if __name__ == "__main__":
    import sys

    path = sys.argv[1] if len(sys.argv) > 1 else "candidate_0.jpg"
    img = cv2.imread(path)
    if img is None:
        print(f"Could not read image: {path}")
        sys.exit(1)

    result = extract_plate_text(img)
    print(result)
