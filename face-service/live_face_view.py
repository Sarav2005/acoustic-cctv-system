"""
Module 5 - live visual demo: real-time webcam view with a green box +
name for recognized people and a red box + "UNKNOWN" for anyone not in
the enrolled database. Good for a viva/demo - lets you show the system
working live rather than just reading JSON off a terminal.

This runs face_recognizer + face_database DIRECTLY in-process (not
through the Flask API) - faster for a live loop since there's no HTTP
round-trip per frame, and this script lives in the same folder as those
modules anyway.

PERFORMANCE NOTE: an earlier version of this script ran detection inline
every few frames, which froze the video window for the duration of each
detection call (buffalo_l takes a few hundred ms on CPU) - visible as a
stutter/freeze rather than smooth video. This version runs detection in
a background thread (see RecognitionWorker below) so the display loop
never blocks - the video stays smooth at full camera frame rate, and the
boxes just update a little behind real-time (however long one detection
pass takes), rather than the whole picture freezing.

Usage:
    python live_face_view.py
        Live webcam window. Press 'q' to quit.

    python live_face_view.py --image path/to/photo.jpg
        Single-image mode (no webcam needed) - draws boxes on one image
        and saves it, for testing the drawing logic or sanity-checking
        results without a live camera running.
"""

import argparse
import threading
import time

import cv2

from face_database import FaceDatabase
from face_recognizer import detect_faces

MATCH_COLOR = (0, 200, 0)      # green (BGR)
UNKNOWN_COLOR = (0, 0, 220)    # red (BGR)
BOX_THICKNESS = 2
FONT = cv2.FONT_HERSHEY_SIMPLEX
FONT_SCALE = 0.6


class RecognitionWorker:
    """
    Runs face detection+matching in a background thread so it never
    blocks the main display loop. The original version ran recognition
    inline every FRAME_SKIP frames, which froze the video window for the
    duration of each detection call (a few hundred ms on CPU with
    buffalo_l) - noticeable as a stutter/freeze rather than smooth video.

    This version decouples the two: the main loop always displays frames
    as fast as the camera provides them, and just draws whatever results
    this worker most recently finished computing - even if that's a
    frame or two behind the current one. The video itself never pauses.
    """

    def __init__(self, db):
        self.db = db
        self._lock = threading.Lock()
        self._latest_frame = None
        self._latest_results = []
        self._running = False
        self._thread = None

    def submit_frame(self, frame):
        """Called from the main loop every frame - just stores a copy for
        the background thread to pick up whenever it's free. Cheap."""
        with self._lock:
            self._latest_frame = frame.copy()

    def get_latest_results(self):
        with self._lock:
            return self._latest_results

    def start(self):
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False
        if self._thread is not None:
            self._thread.join(timeout=2)

    def _loop(self):
        while self._running:
            with self._lock:
                frame = self._latest_frame
            if frame is None:
                time.sleep(0.05)
                continue

            results = get_face_results(self.db, frame)

            with self._lock:
                self._latest_results = results
            # No sleep here on purpose - as soon as one detection pass
            # finishes, immediately pick up whatever the newest submitted
            # frame is and start the next pass. Throughput is naturally
            # capped by how long detection itself takes.


def annotate_frame(frame, face_results):
    """Draw a labeled box for each face result onto frame (in place)."""
    for result in face_results:
        x, y, w, h = result["bbox"]
        is_match = result["status"] == "MATCH"
        color = MATCH_COLOR if is_match else UNKNOWN_COLOR
        label = f"{result['identity']} ({result['confidence']:.2f})" if is_match else "UNKNOWN"

        cv2.rectangle(frame, (x, y), (x + w, y + h), color, BOX_THICKNESS)

        # label background for readability over busy video
        (text_w, text_h), _ = cv2.getTextSize(label, FONT, FONT_SCALE, 2)
        label_y = max(0, y - 8)
        cv2.rectangle(frame, (x, label_y - text_h - 6), (x + text_w + 6, label_y + 2), color, -1)
        cv2.putText(frame, label, (x + 3, label_y - 3), FONT, FONT_SCALE, (255, 255, 255), 2, cv2.LINE_AA)

    return frame


def get_face_results(db, frame):
    """Run detection + matching on a frame, returning the same per-face
    shape app.py's /verify-faces endpoint uses (bbox/status/confidence/
    identity), so this stays consistent with the rest of the project."""
    detected = detect_faces(frame)
    results = []
    for face in detected:
        match = db.match(face["embedding"])
        results.append(
            {
                "bbox": face["bbox"],
                "status": match["status"],
                "confidence": match["confidence"],
                "identity": match["identity"],
            }
        )
    return results


def run_live(camera_index=0):
    db = FaceDatabase()
    print(f"Loaded {len(db.people)} enrolled people.")
    print("Loading InsightFace (first call is slower - loading the model)...")

    cap = cv2.VideoCapture(camera_index)
    if not cap.isOpened():
        print(f"ERROR: could not open webcam (index {camera_index}).")
        return

    worker = RecognitionWorker(db)
    worker.start()

    window_name = "Face Recognition - Live Demo (press Q to quit)"
    print("Running. Press 'q' in the video window to quit.")
    print("(boxes may lag slightly behind your movement - that's expected, "
          "recognition runs in the background so the video itself stays smooth)")

    try:
        while True:
            success, frame = cap.read()
            if not success:
                print("WARNING: failed to read a frame, stopping.")
                break

            worker.submit_frame(frame)
            annotate_frame(frame, worker.get_latest_results())
            cv2.imshow(window_name, frame)

            if cv2.waitKey(1) & 0xFF == ord("q"):
                break
    finally:
        worker.stop()
        cap.release()
        cv2.destroyAllWindows()


def run_single_image(path):
    frame = cv2.imread(path)
    if frame is None:
        print(f"Could not read image: {path}")
        return

    db = FaceDatabase()
    print(f"Loaded {len(db.people)} enrolled people.")
    results = get_face_results(db, frame)
    print(f"Found {len(results)} face(s):")
    for r in results:
        print(f"  {r}")

    annotate_frame(frame, results)
    out_path = "annotated_" + path.split("\\")[-1].split("/")[-1]
    cv2.imwrite(out_path, frame)
    print(f"Saved annotated image to {out_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", help="Run on a single image instead of the live webcam")
    parser.add_argument("--camera", type=int, default=0, help="Webcam index (default 0)")
    args = parser.parse_args()

    if args.image:
        run_single_image(args.image)
    else:
        run_live(camera_index=args.camera)
