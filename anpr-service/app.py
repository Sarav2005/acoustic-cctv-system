"""
Module 4 - ANPR: Flask microservice.

Exposes:
    POST /verify-plate
        multipart/form-data with an 'image' file field
        -> detects/reads the plate and fuzzy-matches it against
           vehicle_database.json
        -> returns JSON verification result

    GET /health
        basic liveness check, also reports which detection backend loaded

Run:
    python app.py
    (listens on 0.0.0.0:5001)

BACKEND: primary detection is easyocr_plate.py (EasyOCR-based region
proposal + deskew + recognition), validated end-to-end against this
project's real CCTV test photos - see easyocr_plate.py's module docstring
for why (4 different classical CV approaches were tried first and none
reliably found real plates in this footage; a trained text detector was
needed). Falls back to the original Tesseract+contour pipeline
(detect_plate.py + ocr_plate.py) if EasyOCR isn't installed, so this
service still runs (with reduced real-world accuracy) on a machine where
EasyOCR/PyTorch couldn't be installed.

This is the service the Spring Boot backend's VerificationService should
call instead of its current always-NO_MATCH stub, for vehicle captures
(car/bike triggers). Face recognition for pedestrian captures is a
separate, later PoC-only module.
"""

import logging

import cv2
import numpy as np
from flask import Flask, jsonify, request

from vehicle_database import VehicleDatabase

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("anpr-service")

app = Flask(__name__)
db = VehicleDatabase()

BACKEND = None
try:
    from easyocr_plate import detect_and_read_plate as _easyocr_detect_and_read
    from easyocr_plate import detect_and_read_plate_burst as _easyocr_detect_and_read_burst

    BACKEND = "easyocr"
    logger.info("Using EasyOCR backend for plate detection")
except Exception as exc:  # ImportError if easyocr/torch isn't installed
    logger.warning("EasyOCR backend unavailable (%s) - falling back to Tesseract+contour pipeline", exc)
    from detect_plate import detect_plate_candidates
    from ocr_plate import extract_plate_text

    BACKEND = "tesseract"


def _read_image_from_request(file_storage):
    data = file_storage.read()
    arr = np.frombuffer(data, dtype=np.uint8)
    image = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    return image


def _verify_with_easyocr(image):
    results = _easyocr_detect_and_read(image)
    logger.info("EasyOCR backend found %d candidate(s)", len(results))

    if not results:
        return {
            "status": "NO_MATCH",
            "reason": "no_plate_region_detected",
            "confidence": 0,
            "vehicle": None,
            "ocr_text": None,
        }

    # already ranked best-first (real DB match quality, then pattern shape)
    top = results[0]
    return {
        "status": top["db_status"],
        "confidence": top["db_confidence"],
        "vehicle": top["vehicle"],
        "ocr_text": top["text"],
        "plate_pattern_matched": top["matched_pattern"],
    }


def _verify_with_easyocr_burst(images):
    result = _easyocr_detect_and_read_burst(images)
    logger.info("EasyOCR burst backend tried %d frame(s)", len(images))

    if result is None:
        return {
            "status": "NO_MATCH",
            "reason": "no_plate_region_detected",
            "confidence": 0,
            "vehicle": None,
            "ocr_text": None,
        }

    return {
        "status": result["db_status"],
        "confidence": result["db_confidence"],
        "vehicle": result["vehicle"],
        "ocr_text": result["text"],
        "plate_pattern_matched": result["matched_pattern"],
        "frame_index": result["frame_index"],
    }


def _verify_with_tesseract(image):
    candidates = detect_plate_candidates(image)
    logger.info("Tesseract backend found %d plate candidate(s)", len(candidates))

    if not candidates:
        return {
            "status": "NO_MATCH",
            "reason": "no_plate_region_detected",
            "confidence": 0,
            "vehicle": None,
            "ocr_text": None,
        }

    ocr_result = None
    for i, candidate in enumerate(candidates):
        result = extract_plate_text(candidate["crop"])
        if result is not None:
            logger.info("Candidate %d OCR: %s", i, result["text"])
            if result["matched_pattern"]:
                ocr_result = result
                break
            if ocr_result is None:
                ocr_result = result

    if ocr_result is None:
        return {
            "status": "NO_MATCH",
            "reason": "ocr_failed_on_all_candidates",
            "confidence": 0,
            "vehicle": None,
            "ocr_text": None,
        }

    match = db.match_with_correction(ocr_result["text"])
    match["ocr_text"] = ocr_result["text"]
    match["plate_pattern_matched"] = ocr_result["matched_pattern"]
    return match


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "vehicles_loaded": len(db.vehicles), "backend": BACKEND})


@app.route("/verify-plate", methods=["POST"])
def verify_plate():
    """
    Accepts either one image (same as before, fully backward-compatible)
    or several under the same 'image' field for multi-frame voting - e.g.
    curl -F "image=@frame1.jpg" -F "image=@frame2.jpg" -F "image=@frame3.jpg" ...

    With multiple images and the EasyOCR backend, tries each frame in
    turn and returns as soon as one produces a real database MATCH
    (see easyocr_plate.detect_and_read_plate_burst's docstring for why
    this matters - single-frame OCR at this crop resolution isn't
    perfectly deterministic, confirmed by real testing on this project's
    own CCTV photos).
    """
    if "image" not in request.files:
        return jsonify({"error": "no 'image' file field in request"}), 400

    file_storages = request.files.getlist("image")
    images = []
    for fs in file_storages:
        img = _read_image_from_request(fs)
        if img is not None:
            images.append(img)

    if not images:
        return jsonify({"error": "could not decode any image"}), 400

    if len(images) == 1:
        result = _verify_with_easyocr(images[0]) if BACKEND == "easyocr" else _verify_with_tesseract(images[0])
    elif BACKEND == "easyocr":
        result = _verify_with_easyocr_burst(images)
    else:
        # Tesseract backend has no burst-voting implementation yet - just
        # use the first frame, same as before.
        result = _verify_with_tesseract(images[0])

    return jsonify(result)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5001, debug=False)
