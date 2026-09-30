"""
Module 5 - Face recognition PoC: Flask microservice.

Model choice: InsightFace's buffalo_l pack (RetinaFace + ArcFace r50) -
the strongest widely-used open-source option, chosen once disk space
stopped being the limiting factor for this project (Module 4's ANPR work
had to work around a tight disk budget; this project's dev machine turned
out to have plenty of room, so accuracy took priority here). Runs on ONNX
Runtime, not PyTorch, so it installs light regardless.

LICENSING NOTE: InsightFace's pretrained models are for non-commercial/
research use - fine for a college project, worth a one-line mention in
your report if this ever comes up.

Exposes:
    POST /verify-faces
        multipart/form-data with an 'image' file field
        -> detects every face in the frame, matches each against the
           enrolled database
        -> ALSO runs YOLOv8n person detection on the same frame,
           independent of face visibility - this backs the "keep
           monitoring while someone is present, even if their face
           isn't visible right now" logic (live_infer.py's debounce),
           as opposed to any_unknown, which backs the separate "keep
           monitoring while a visible face is still unidentified" logic
        -> returns a per-face list, any_unknown, all_matched, PLUS
           person_detected/person_count from the YOLO pass

    POST /enroll
        multipart/form-data: 'image' file + 'name' field, optional
        'flat_number' and 'status' fields
        -> computes an embedding from the photo and adds/appends it to
           that person's enrolled record

    GET /health
        basic liveness check, reports how many people are enrolled

Run:
    python app.py
    (listens on 0.0.0.0:5002 - a different port from the ANPR service on
    5001, so both microservices can run side by side)
"""

import logging

import cv2
import numpy as np
from flask import Flask, jsonify, request

from face_recognizer import detect_faces, detect_person_present
from face_database import FaceDatabase

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("face-service")

app = Flask(__name__)
db = FaceDatabase()


def _read_image_from_request(file_storage):
    data = file_storage.read()
    arr = np.frombuffer(data, dtype=np.uint8)
    return cv2.imdecode(arr, cv2.IMREAD_COLOR)


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "people_enrolled": len(db.people)})


@app.route("/verify-faces", methods=["POST"])
def verify_faces():
    if "image" not in request.files:
        return jsonify({"error": "no 'image' file field in request"}), 400

    image = _read_image_from_request(request.files["image"])
    if image is None:
        return jsonify({"error": "could not decode image"}), 400

    detected = detect_faces(image)
    logger.info("Detected %d face(s)", len(detected))

    person_present, person_count = detect_person_present(image)
    logger.info("Person detector: present=%s count=%d", person_present, person_count)

    faces_result = []
    for face in detected:
        match = db.match(face["embedding"])
        faces_result.append(
            {
                "bbox": face["bbox"],
                "det_score": face["det_score"],
                "status": match["status"],
                "confidence": match["confidence"],
                "identity": match["identity"],
                "flat_number": match["flat_number"],
            }
        )

    any_unknown = any(f["status"] == "NO_MATCH" for f in faces_result)
    all_matched = len(faces_result) > 0 and not any_unknown

    return jsonify(
        {
            "face_count": len(faces_result),
            "faces": faces_result,
            "any_unknown": any_unknown,
            "all_matched": all_matched,
            "person_detected": person_present,
            "person_count": person_count,
        }
    )


@app.route("/enroll", methods=["POST"])
def enroll():
    if "image" not in request.files:
        return jsonify({"error": "no 'image' file field in request"}), 400
    name = request.form.get("name")
    if not name:
        return jsonify({"error": "'name' form field is required"}), 400

    flat_number = request.form.get("flat_number")
    status = request.form.get("status", "resident")

    image = _read_image_from_request(request.files["image"])
    if image is None:
        return jsonify({"error": "could not decode image"}), 400

    detected = detect_faces(image)
    if len(detected) == 0:
        return jsonify({"error": "no face detected in enrollment photo"}), 400
    if len(detected) > 1:
        return jsonify({"error": f"found {len(detected)} faces - enrollment photos need exactly one person"}), 400

    person = db.enroll(name, detected[0]["embedding"], flat_number=flat_number, status=status)
    return jsonify({"name": person["name"], "embeddings_stored": len(person["embeddings"])})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5002, debug=False)