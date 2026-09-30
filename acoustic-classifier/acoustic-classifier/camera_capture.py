"""
Captures still frame(s) from the laptop's default webcam.

This is the direct stand-in for what the ESP32-CAM will do later: same job
(capture image(s) when told to), same output (JPEG file(s) on disk) --  only
the camera hardware changes later, not the interface this module exposes.

Usage as a library:
    from camera_capture import capture_frame, capture_burst, monitor_frames, video_path_for_event
    path = capture_frame("my_photo.jpg")               # single frame
    paths = capture_burst("event123", n_frames=4)       # fixed burst
    for frame in monitor_frames():                      # continuous monitoring
        ...                                              # see monitor_frames() docstring

Usage standalone (for testing the camera works at all):
    python camera_capture.py
"""

import os
import queue
import threading
import time

import cv2

CAPTURE_DIR = "webcam_captures"
VIDEO_DIR = "webcam_recordings"

DEFAULT_BURST_FRAMES = 4
DEFAULT_BURST_DELAY_SECONDS = 0.15

DEFAULT_MONITOR_INTERVAL_SECONDS = 1.0
DEFAULT_MONITOR_MAX_DURATION_SECONDS = 30
DEFAULT_RECORD_FPS = 15

LIVE_FRAME_FILENAME = "live_frame.jpg"

# --- Dashboard live-feed overlay state ----------------------------------
#
# Set by the caller (live_infer.py) whenever a fresh verification result
# arrives (once per second), and read by the background recording
# thread on EVERY frame it publishes (~15/sec) - see _draw_overlay() and
# _MonitorSession._run(). This is what makes the live feed show both
# smooth continuous motion (published every tick) AND a persistent
# detection box (redrawn onto every tick using the latest known result,
# not just the one tick a verification happened to land on).
#
# Simple module-level state is safe here without explicit locking: only
# one monitoring session is ever active at a time in this app, and
# CPython's GIL makes a plain reference reassignment (the only operation
# either side performs on this variable) atomic.
_current_overlay_faces = None


def set_live_overlay(faces):
    """Called by live_infer.py after each verification sample - faces is
    the list of {x,y,w,h,status,identity} dicts the backend returned (or
    None/empty for PLATE events, which don't have box data yet)."""
    global _current_overlay_faces
    _current_overlay_faces = faces


def clear_live_overlay():
    global _current_overlay_faces
    _current_overlay_faces = None


def _draw_overlay(frame, faces):
    """Draws a rectangle + MATCH/identity label over each face in faces
    onto a COPY of frame (the original is left untouched, since it's
    also what gets written to the actual video file). Returns frame
    unchanged (still copied) if faces is empty/None."""
    annotated = frame.copy()
    for face in faces or []:
        x, y, w, h = face["x"], face["y"], face["w"], face["h"]
        matched = face.get("status") == "MATCH"
        color = (0, 200, 0) if matched else (0, 0, 255)  # BGR: green if matched, red otherwise
        label = face.get("identity") if (matched and face.get("identity")) else "Unknown"
        cv2.rectangle(annotated, (x, y), (x + w, y + h), color, 2)
        cv2.putText(annotated, label, (x, max(0, y - 8)), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)
    return annotated


def capture_frame(filename=None):
    paths = capture_burst(
        prefix=os.path.splitext(filename)[0] if filename else None,
        n_frames=1,
    )
    return paths[0] if paths else None


def capture_burst(prefix=None, n_frames=DEFAULT_BURST_FRAMES, delay_seconds=DEFAULT_BURST_DELAY_SECONDS):
    os.makedirs(CAPTURE_DIR, exist_ok=True)

    if prefix is None:
        prefix = f"capture_{int(time.time() * 1000)}"

    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        print("ERROR: could not open webcam (index 0). Is it in use by another app?")
        return []

    for _ in range(5):
        cap.read()

    saved_paths = []
    for i in range(n_frames):
        success, frame = cap.read()
        if not success:
            print(f"  WARNING: failed to read frame {i} of burst.")
            continue

        filename = f"{prefix}_{i}.jpg" if n_frames > 1 else f"{prefix}.jpg"
        filepath = os.path.join(CAPTURE_DIR, filename)
        cv2.imwrite(filepath, frame)
        saved_paths.append(filepath)

        if i < n_frames - 1:
            time.sleep(delay_seconds)

    cap.release()

    if not saved_paths:
        print("ERROR: every frame in the burst failed to read.")

    return saved_paths


class _MonitorSession:
    """
    Runs the actual webcam read loop in a background thread, so
    continuous video recording (and now the live-feed publish) keeps
    going at a steady rate even while the CALLER is blocked on a slow
    backend/face-service network call.
    """

    def __init__(self, interval_seconds, max_duration_seconds, record_path, record_fps):
        self.interval_seconds = interval_seconds
        self.max_duration_seconds = max_duration_seconds
        self.record_path = record_path
        self.record_fps = record_fps
        self._sample_queue = queue.Queue(maxsize=1)
        self._stop_event = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self._thread.start()

    def stop(self):
        self._stop_event.set()
        self._thread.join(timeout=5)

    def samples(self):
        while not self._stop_event.is_set() or not self._sample_queue.empty():
            try:
                frame = self._sample_queue.get(timeout=0.5)
            except queue.Empty:
                continue
            yield frame

    def _run(self):
        clear_live_overlay()  # don't show a stale box/name from a previous event

        cap = cv2.VideoCapture(0)
        if not cap.isOpened():
            print("ERROR: could not open webcam (index 0). Is it in use by another app?")
            self._stop_event.set()
            return

        for _ in range(5):
            cap.read()

        writer = None
        if self.record_path is not None:
            os.makedirs(os.path.dirname(self.record_path) or ".", exist_ok=True)
            width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
            height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
            fourcc = cv2.VideoWriter_fourcc(*"mp4v")
            writer = cv2.VideoWriter(self.record_path, fourcc, self.record_fps, (width, height))
            if not writer.isOpened():
                print(f"WARNING: could not open video writer for {self.record_path}, "
                      f"recording disabled this session (verification sampling continues normally).")
                writer = None

        start_time = time.time()
        last_sample_time = start_time
        target_frame_interval = 1.0 / self.record_fps

        try:
            while not self._stop_event.is_set() and (time.time() - start_time) < self.max_duration_seconds:
                loop_start = time.time()
                success, frame = cap.read()
                if not success:
                    print("WARNING: failed to read a frame during monitoring, stopping.")
                    break

                if writer is not None:
                    writer.write(frame)

                # Publish EVERY frame (not just verification samples) as
                # the live-feed preview, with whatever the latest known
                # detection overlay is burned in - this is what makes the
                # dashboard's live feed look continuously live instead of
                # updating once a second (or once total, if verification
                # resolves on the very first sample).
                save_live_frame(_draw_overlay(frame, _current_overlay_faces))

                now = time.time()
                if (now - last_sample_time) >= self.interval_seconds:
                    last_sample_time = now
                    if self._sample_queue.full():
                        try:
                            self._sample_queue.get_nowait()
                        except queue.Empty:
                            pass
                    self._sample_queue.put(frame.copy())

                elapsed = time.time() - loop_start
                if elapsed < target_frame_interval:
                    time.sleep(target_frame_interval - elapsed)
        finally:
            cap.release()
            if writer is not None:
                writer.release()
            self._stop_event.set()


def monitor_frames(
    interval_seconds=DEFAULT_MONITOR_INTERVAL_SECONDS,
    max_duration_seconds=DEFAULT_MONITOR_MAX_DURATION_SECONDS,
    record_path=None,
    record_fps=DEFAULT_RECORD_FPS,
):
    session = _MonitorSession(interval_seconds, max_duration_seconds, record_path, record_fps)
    session.start()
    try:
        yield from session.samples()
    finally:
        session.stop()


def save_frame(frame, filename):
    os.makedirs(CAPTURE_DIR, exist_ok=True)
    filepath = os.path.join(CAPTURE_DIR, filename)
    cv2.imwrite(filepath, frame)
    return filepath


def save_live_frame(frame):
    """Overwrites the single 'live feed' preview file. Called ~15x/sec by
    the background recording thread during monitoring (see
    _MonitorSession._run).

    Plain direct overwrite - NOT atomic rename. Confirmed via testing
    that atomic rename (write to a .tmp file, then os.replace()) fails
    on Windows here with PermissionError, because the dashboard has this
    exact file open for reading (polling it every ~400ms) at the same
    time - Windows blocks a rename over a file another process has open,
    more strictly than it blocks a plain overwrite. The dashboard's
    reader already tolerates an occasional torn/corrupt read via a
    try/catch-and-retry-next-poll, which is the safety net this relies
    on instead."""
    os.makedirs(CAPTURE_DIR, exist_ok=True)
    filepath = os.path.join(CAPTURE_DIR, LIVE_FRAME_FILENAME)
    cv2.imwrite(filepath, frame)
    return filepath

def video_path_for_event(event_id):
    os.makedirs(VIDEO_DIR, exist_ok=True)
    return os.path.join(VIDEO_DIR, f"{event_id}.mp4")


if __name__ == "__main__":
    print("Testing capture_burst (4 frames)...")
    paths = capture_burst("test_burst", n_frames=4)
    if paths:
        print(f"Saved {len(paths)} frame(s):")
        for p in paths:
            print(f"  {p}")
    else:
        print("Burst capture failed.")

    print("\nTesting monitor_frames with recording (5 seconds, 1 sample/sec, 15fps video + live feed)...")
    test_video_path = video_path_for_event("standalone_test")
    count = 0
    for frame in monitor_frames(interval_seconds=1.0, max_duration_seconds=5, record_path=test_video_path):
        count += 1
        path = save_frame(frame, f"test_monitor_{count}.jpg")
        print(f"  sample {count}: saved to {path}")
    print(f"Monitoring test done, {count} sample(s) captured. Video saved to {test_video_path}")