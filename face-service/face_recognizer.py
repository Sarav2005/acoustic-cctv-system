"""
Module 5 - Face recognition PoC: detection + embedding extraction.

Uses InsightFace's buffalo_l pack (RetinaFace detector + ArcFace r50
recognizer) - the strongest widely-used open-source face recognition
model, chosen once disk space stopped being a constraint (see app.py's
module docstring for the earlier SFace-vs-buffalo_l discussion). Runs on
ONNX Runtime, not PyTorch, so it's much lighter to install than EasyOCR
was for Module 4.

NOTE ON LICENSING: InsightFace's pretrained model packs (including
buffalo_l) are released for non-commercial/research use. Fine for a
college project - just don't skip mentioning it if this ever gets
repurposed commercially.

--- Added: person-presence detection (continuous-monitoring fix) --------
Face detection alone can't tell "nobody's here" apart from "someone's
here but their face isn't visible" (turned away, looking down, partial
occlusion) - both give faceCount == 0. The continuous-monitoring loop
needs a presence signal that's independent of face visibility, so it
doesn't wrongly conclude someone left just because they're not facing
the camera. YOLOv8n (nano) filtered to the COCO "person" class does
this: it detects a human body/silhouette from any angle, not just faces.
It's a separate, much lighter model than buffalo_l (~6MB, auto-downloaded
on first run) and runs fast enough for the ~1 frame/sec sampling rate
used here.
"""

from insightface.app import FaceAnalysis
from ultralytics import YOLO

_face_app = None    # lazy-loaded singleton - model load takes a few seconds
_person_model = None  # lazy-loaded singleton, separate from _face_app

COCO_PERSON_CLASS_ID = 0
PERSON_CONF_THRESHOLD = 0.45  # YOLO confidence floor for counting a detection as "a person"


def get_face_app():
    global _face_app
    if _face_app is None:
        _face_app = FaceAnalysis(name="buffalo_l")
        # ctx_id=-1 forces CPU. det_size is the internal detection
        # resolution - 640x640 is InsightFace's own default and a
        # reasonable balance of accuracy vs speed for a laptop webcam
        # frame; raise it if faces are small/distant, lower it for speed.
        _face_app.prepare(ctx_id=-1, det_size=(640, 640))
    return _face_app


def get_person_model():
    global _person_model
    if _person_model is None:
        _person_model = YOLO("yolov8n.pt")
    return _person_model


def detect_faces(image):
    """
    Run detection + embedding extraction on a BGR image (as loaded by
    cv2.imread or decoded from an upload).

    Returns a list of dicts, one per detected face:
        {"bbox": (x, y, w, h), "embedding": list[float], "det_score": float}
    Empty list if no faces found.
    """
    app = get_face_app()
    faces = app.get(image)

    results = []
    for face in faces:
        x0, y0, x1, y1 = face.bbox.astype(int)
        w, h = x1 - x0, y1 - y0
        results.append(
            {
                "bbox": (int(x0), int(y0), int(w), int(h)),
                "embedding": face.normed_embedding.tolist(),
                "det_score": float(face.det_score),
            }
        )
    return results


def detect_person_present(image):
    """
    Run person (body) detection on a BGR image, independent of face
    visibility. Returns (person_present: bool, person_count: int).

    This is the presence signal the continuous-monitoring loop should
    use for "has everyone left?" - detect_faces() alone answers "is a
    face visible right now?", which is a different question.
    """
    model = get_person_model()
    results = model.predict(
        image,
        classes=[COCO_PERSON_CLASS_ID],
        conf=PERSON_CONF_THRESHOLD,
        verbose=False,
    )
    boxes = results[0].boxes
    count = 0 if boxes is None else len(boxes)
    return count > 0, count


if __name__ == "__main__":
    import sys

    import cv2

    path = sys.argv[1] if len(sys.argv) > 1 else "test.jpg"
    img = cv2.imread(path)
    if img is None:
        print(f"Could not read image: {path}")
        sys.exit(1)

    print("Loading InsightFace buffalo_l (first run downloads ~280MB)...")
    results = detect_faces(img)
    print(f"Found {len(results)} face(s)")
    for i, r in enumerate(results):
        print(f"  [{i}] bbox={r['bbox']} det_score={r['det_score']:.3f} embedding_dim={len(r['embedding'])}")

    print("Loading YOLOv8n person model (first run downloads ~6MB)...")
    present, count = detect_person_present(img)
    print(f"Person present: {present} (count={count})")