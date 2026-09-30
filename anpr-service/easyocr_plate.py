"""
Module 4 - ANPR: alternate detection backend using EasyOCR's detector,
paired with the existing Tesseract-based reader from ocr_plate.py.

WHY THIS EXISTS: four different classical CV techniques (contour+scoring,
Sobel+morphological-close, OpenCV's pretrained Haar cascade, and
Tesseract's own sparse-text spotting) were all tested directly against a
real CCTV photo from this project and none reliably localized the actual
plate - it's small, low-contrast, and sits next to a reflective chrome
trim strip that every one of those heuristics mistook for something
plate-like.

EasyOCR bundles a trained scene-text detector (CRAFT). A first version of
this module used EasyOCR's own readtext() (detection + recognition in one
call) end-to-end, but real testing showed the problem: CRAFT's own
recognizer, reading directly off the low-res full frame, merged the plate
and the trim strip above it into one region and produced a near-garbage
read (~0.04 confidence) that got thrown away by a confidence filter -
even though the DETECTED BOX actually did overlap the real plate.

This version adds a THIRD fix, found only after testing directly against
the real plate crop: the region EasyOCR (and everything else) proposed
contained the plate photographed at a ~15-30 degree angle (CCTV cameras
rarely shoot plates straight-on), and no amount of upscaling/thresholding
tuning could get Tesseract to read rotated text - a human can read a
tilted plate easily, but fixed-orientation OCR engines generally can't.
Fix: the plate is much brighter than the car body around it, so isolate
it by brightness, find its true rotated rectangle, and warp/crop to an
upright, tightly-cropped plate image before running OCR at all. On top of
that, at the resulting small crop size (a CCTV plate can end up well
under 25px tall), EasyOCR's own recognizer read it more reliably in
testing than Tesseract does - so this version tries EasyOCR's recognizer
on the deskewed crop first, falling back to the Tesseract pipeline if
that doesn't produce a usable read.

STATUS: could not be fully tested end-to-end before being handed over -
EasyOCR needs PyTorch, which doesn't fit the dev sandbox's disk quota.
Real testing so far (by the user, on their own machine) confirms
reader.readtext() DOES detect a box overlapping the real plate on
car1.jpeg, which is why this split approach is worth trying. Please
verify with the __main__ block below before trusting it for the demo.

Usage:
    from easyocr_plate import detect_and_read_plate
    result = detect_and_read_plate(image)   # image = cv2 BGR array
    # result: list of dicts, sorted best-first:
    #   {"text": str, "matched_pattern": bool, "bbox": (x,y,w,h)}
"""

import cv2
import numpy as np

from ocr_plate import extract_plate_text, PLATE_PATTERN, _clean_text
from vehicle_database import VehicleDatabase

MIN_ASPECT_RATIO = 0.8   # looser than ocr_plate's own defaults - EasyOCR's
MAX_ASPECT_RATIO = 8.0   # proposed boxes may include a margin/merged text

MIN_BOX_WIDTH = 25
MIN_BOX_HEIGHT = 10

_reader = None  # lazy-loaded singleton
_db = None


def _get_reader():
    global _reader
    if _reader is None:
        import easyocr  # imported lazily so this file doesn't hard-crash
                         # the rest of the service if easyocr isn't installed
        _reader = easyocr.Reader(["en"], gpu=False)
    return _reader


def _get_db():
    global _db
    if _db is None:
        _db = VehicleDatabase()
    return _db


def _deskew_plate_crop(image, x0, y0, w, h, debug=False):
    """
    Given a rough (possibly rotated-text, possibly loosely-margined)
    candidate box, isolate just the plate itself and return an upright,
    tightly-cropped BGR image ready for OCR.

    Real-world testing found the actual bottleneck wasn't detection or
    even the OCR engine, but that CCTV plates are often photographed at
    an angle - EasyOCR's proposed box was a merged, unrotated rectangle
    containing a plate tilted ~15-30 degrees inside it, which no OCR
    engine could read regardless of preprocessing. Fix: the plate itself
    is much brighter than the surrounding car body/background, so
    isolate it by brightness, find its true rotated rectangle via
    minAreaRect, and warp/crop to just that upright region.
    """
    crop = image[y0:y0 + h, x0:x0 + w]
    if crop.size == 0:
        return None

    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    _, bright_mask = cv2.threshold(gray, 120, 255, cv2.THRESH_BINARY)
    contours, _ = cv2.findContours(bright_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return crop  # nothing brighter found - fall back to the raw crop

    c = max(contours, key=cv2.contourArea)
    if cv2.contourArea(c) < 0.05 * crop.shape[0] * crop.shape[1]:
        return crop  # bright region too small to trust - fall back

    (cx, cy), (rw, rh), angle = cv2.minAreaRect(c)
    if rw < rh:
        rw, rh = rh, rw
        angle += 90

    M = cv2.getRotationMatrix2D((cx, cy), angle, 1.0)
    ch, cw = gray.shape
    rotated = cv2.warpAffine(crop, M, (cw, ch), borderValue=(0, 0, 0))

    shrink = 3  # pull in slightly from the plate's own border/edge
    px0 = max(0, int(cx - rw / 2 + shrink))
    py0 = max(0, int(cy - rh / 2 + shrink))
    px1 = min(cw, int(cx + rw / 2 - shrink))
    py1 = min(ch, int(cy + rh / 2 - shrink))
    if px1 <= px0 or py1 <= py0:
        return crop

    tight = rotated[py0:py1, px0:px1]
    if debug:
        print(f"      deskew: angle={angle:.1f} rect_size=({rw:.0f}x{rh:.0f}) -> tight crop {tight.shape}")
    return tight if tight.size > 0 else crop


def _recognize_with_easyocr(reader, crop):
    """Run EasyOCR's recognizer only (no detection) on an already-located,
    already-deskewed crop - EasyOCR's network handles low-res/blurry real
    scene text better than Tesseract does in practice, which matters once
    the crop is this small (a CCTV plate can be under 25px tall)."""
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    try:
        results = reader.recognize(gray)
    except Exception:
        return None
    if not results:
        return None
    # recognize() on a single pre-cropped region returns one (bbox, text, conf)
    _bbox, text, confidence = results[0]
    cleaned = _clean_text(text)
    if len(cleaned) < 4:
        return None
    return {"text": cleaned, "matched_pattern": bool(PLATE_PATTERN.match(cleaned)), "confidence": float(confidence)}


def detect_and_read_plate(image, debug=False):
    """
    1. EasyOCR readtext() for region proposals (proven to find a box
       overlapping the real plate, even though its own end-to-end read
       of that box was unreliable).
    2. Deskew + tightly crop each proposed region to just the plate.
    3. Try EasyOCR's recognizer on the clean crop first (better on
       low-res/blurry real photos); fall back to the existing Tesseract
       pipeline if that doesn't produce a usable read.

    Returns a list of dicts, best first:
        {"text": str, "matched_pattern": bool, "bbox": (x,y,w,h), "source": "easyocr"|"tesseract"}
    """
    reader = _get_reader()
    img_h, img_w = image.shape[:2]

    raw_results = reader.readtext(
        image, mag_ratio=2.0, low_text=0.3, text_threshold=0.5, min_size=8
    )
    boxes = []
    for box_points, _text, _confidence in raw_results:
        xs = [p[0] for p in box_points]
        ys = [p[1] for p in box_points]
        boxes.append([min(xs), max(xs), min(ys), max(ys)])

    if debug:
        print(f"  EasyOCR proposed {len(boxes)} raw region(s)")

    results = []
    for box in boxes:
        x_min, x_max, y_min, y_max = box
        x0, y0 = max(0, int(x_min)), max(0, int(y_min))
        x1, y1 = min(img_w, int(x_max)), min(img_h, int(y_max))
        w, h = x1 - x0, y1 - y0
        if w <= 0 or h <= 0:
            continue
        aspect_ratio = w / h
        if debug:
            print(f"    bbox=({x0},{y0},{w},{h}) aspect={aspect_ratio:.2f}")
        if w < MIN_BOX_WIDTH or h < MIN_BOX_HEIGHT:
            continue
        if not (MIN_ASPECT_RATIO <= aspect_ratio <= MAX_ASPECT_RATIO):
            continue

        mx, my = int(0.08 * w) + 4, int(0.25 * h) + 4  # generous margin - deskew needs
        cx0, cy0 = max(0, x0 - mx), max(0, y0 - my)     # room around the plate to find it
        cx1, cy1 = min(img_w, x1 + mx), min(img_h, y1 + my)
        rough_w, rough_h = cx1 - cx0, cy1 - cy0
        if rough_w <= 0 or rough_h <= 0:
            continue

        deskewed = _deskew_plate_crop(image, cx0, cy0, rough_w, rough_h, debug=debug)
        if deskewed is None or deskewed.size == 0:
            continue

        ocr_result = _recognize_with_easyocr(reader, deskewed)
        source = "easyocr"
        if ocr_result is None:
            ocr_result = extract_plate_text(deskewed)
            source = "tesseract"

        if ocr_result is None:
            if debug:
                print(f"      -> OCR failed (tried easyocr recognizer + tesseract)")
            continue

        if debug:
            print(f"      -> [{source}] {ocr_result['text']!r} pattern_match={ocr_result['matched_pattern']}")

        db_match = _get_db().match_with_correction(ocr_result["text"])
        if debug and db_match["status"] == "MATCH":
            print(f"      -> DB MATCH: {db_match['vehicle']['plate']} (confidence {db_match['confidence']})")

        results.append(
            {
                "text": ocr_result["text"],
                "matched_pattern": ocr_result["matched_pattern"],
                "bbox": (cx0, cy0, rough_w, rough_h),
                "source": source,
                "db_status": db_match["status"],
                "db_confidence": db_match["confidence"],
                "vehicle": db_match["vehicle"],
            }
        )

    # Rank by actual database match quality first (the strongest real
    # signal - does this read correspond to a known plate), THEN by
    # regex pattern-shape as a tiebreak. Earlier versions ranked by
    # pattern-match alone, which meant a good-but-slightly-noisy real
    # read (e.g. one extra/misread character) could rank BELOW pure
    # garbage reads that happened to not match the pattern either -
    # both would tie at "no pattern match" with no further signal to
    # separate them.
    results.sort(key=lambda r: (r["db_status"] == "MATCH", r["db_confidence"], r["matched_pattern"]), reverse=True)
    return results


def detect_and_read_plate_burst(images, debug=False):
    """
    Multi-frame voting: try detect_and_read_plate() on each image in
    turn, stopping as soon as one produces an actual database MATCH.

    WHY: real testing (car1.jpeg vs car2.jpeg - the same physical plate,
    6 seconds apart) showed EasyOCR's recognizer isn't perfectly
    deterministic at this crop resolution - one frame read correctly,
    the near-identical next frame didn't. This is a known, expected
    characteristic of OCR at this image quality, not something further
    parameter tuning reliably fixes - so instead of trusting one frame,
    try several from the same trigger event. Each additional frame is an
    independent attempt; a failed frame doesn't reduce the odds of a
    later one succeeding.

    If no frame produces a MATCH, returns the single best-scoring
    candidate seen across all frames (by db_confidence, then
    matched_pattern) so there's still a best-effort answer, tagged
    NO_MATCH.

    images: list of BGR np.ndarrays (e.g. several consecutive webcam
    frames captured after a trigger).

    Returns the same shape as one element of detect_and_read_plate()'s
    result list, plus "frame_index" showing which image in the burst
    produced it. Returns None if every frame found zero candidates.
    """
    best_overall = None
    for i, image in enumerate(images):
        if debug:
            print(f"--- frame {i} ---")
        results = detect_and_read_plate(image, debug=debug)
        if not results:
            continue

        top = results[0]
        if top["db_status"] == "MATCH":
            top["frame_index"] = i
            if debug:
                print(f"  MATCH found on frame {i}, stopping burst early")
            return top

        if best_overall is None or (
            (top["db_status"] == "MATCH", top["db_confidence"], top["matched_pattern"])
            > (best_overall["db_status"] == "MATCH", best_overall["db_confidence"], best_overall["matched_pattern"])
        ):
            top["frame_index"] = i
            best_overall = top

    return best_overall


if __name__ == "__main__":
    import sys

    path = sys.argv[1] if len(sys.argv) > 1 else "car1.jpeg"
    debug = "--debug" in sys.argv
    img = cv2.imread(path)
    if img is None:
        print(f"Could not read image: {path}")
        sys.exit(1)

    print("Loading EasyOCR (first run downloads model weights, ~1-2 min)...")
    results = detect_and_read_plate(img, debug=debug)
    print(f"Found {len(results)} OCR-readable candidate(s)")
    for r in results:
        print(f"  text={r['text']!r} matched_pattern={r['matched_pattern']} bbox={r['bbox']} "
              f"source={r['source']} db_status={r['db_status']} db_confidence={r['db_confidence']}")

