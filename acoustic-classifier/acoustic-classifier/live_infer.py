"""
Continuously listens on your laptop's default microphone, classifies each
1-second window using the trained model, and POSTs actionable detections
(car/bike/footsteps/scream) to the Spring Boot backend's /api/audio/events
endpoint -- the same endpoint you already tested with curl.

This is the direct laptop-mic stand-in for what the ESP32 + I2S mic will do
later: same backend contract either way.

Usage:
    python live_infer.py
Press Ctrl+C to stop.
"""

import json
import os
import threading
import time

import numpy as np
import requests
import sounddevice as sd
import tensorflow as tf

from config import (
    SAMPLE_RATE, CLIP_DURATION_SECONDS, N_MELS, N_FFT, HOP_LENGTH, N_FRAMES,
    CLASSES, ACTIONABLE_CLASSES, KERAS_MODEL_PATH, BACKEND_AUDIO_EVENT_URL,
    BACKEND_CAPTURE_URL, NODE_ID,
)
from preprocess import extract_log_mel, normalize_loudness
from camera_capture import monitor_frames, save_frame, video_path_for_event, set_live_overlay

BACKEND_VIDEO_CAPTURE_URL = BACKEND_CAPTURE_URL + "/video"

MIN_CONFIDENCE_TO_REPORT = 0.5
COOLDOWN_SECONDS = 3.0

MONITOR_INTERVAL_SECONDS = 1.0
MONITOR_MAX_DURATION_SECONDS = 30
FACE_DEBOUNCE_EMPTY_SAMPLES = 5
VERIFIED_STATUSES = {"MATCH"}

PLATE_EXTRA_RECORD_SECONDS = 5

LIVE_AUDIO_DIR = "webcam_captures"
LIVE_AUDIO_PATH = os.path.join(LIVE_AUDIO_DIR, "live_audio.json")
LIVE_AUDIO_POINTS = 150
LIVE_AUDIO_VISUAL_GAIN = 10.0

# --- Camera monitoring now runs in its own thread ------------------------
#
# capture_and_monitor() blocks for the whole duration of a monitoring
# session (up to MONITOR_MAX_DURATION_SECONDS, longer still for PLATE
# events with the extra-recording tail). Calling it directly from the
# main loop meant audio capture - and the live-audio-panel updates that
# depend on it - completely froze for that entire window, since
# everything ran on one thread. Confirmed via testing: the live audio
# panel visibly stopped moving during any camera-triggered event.
#
# Fix: run capture_and_monitor() in a background thread so the main
# loop keeps capturing and classifying audio (and updating the live
# panel) every second, uninterrupted. _monitoring_lock ensures only one
# camera session runs at a time - a second trigger while one is already
# active is skipped rather than trying to open the webcam twice.
_monitoring_lock = threading.Lock()
_monitoring_active = False


def save_live_audio(audio_window, label, confidence, triggered):
    """Writes a small downsampled amplitude summary of the current 1s
    audio window, plus the classifier's verdict for it, for the
    dashboard's live waveform panel."""
    os.makedirs(LIVE_AUDIO_DIR, exist_ok=True)
    chunk_size = max(1, len(audio_window) // LIVE_AUDIO_POINTS)
    points = [
        float(min(1.0, np.max(np.abs(audio_window[i:i + chunk_size])) * LIVE_AUDIO_VISUAL_GAIN))
        for i in range(0, len(audio_window), chunk_size)
    ][:LIVE_AUDIO_POINTS]
    payload = {"points": points, "label": label, "confidence": confidence, "triggered": triggered}
    tmp_path = LIVE_AUDIO_PATH + ".tmp"
    with open(tmp_path, "w") as f:
        json.dump(payload, f)
    os.replace(tmp_path, LIVE_AUDIO_PATH)


def classify_window(model, audio_window):
    audio_window = normalize_loudness(audio_window)
    spec = extract_log_mel(audio_window)
    spec = (spec + 80.0) / 80.0
    spec = np.clip(spec, 0.0, 1.0)
    spec = spec[np.newaxis, ...]

    probs = model.predict(spec, verbose=0)[0]
    predicted_idx = int(np.argmax(probs))
    return CLASSES[predicted_idx], float(probs[predicted_idx])


def post_audio_event(label, confidence):
    payload = {"nodeId": NODE_ID, "label": label.upper(), "confidence": confidence}
    try:
        response = requests.post(BACKEND_AUDIO_EVENT_URL, json=payload, timeout=5)
        response.raise_for_status()
        data = response.json()
        print(f"  -> backend: eventId={data['eventId']} triggerCamera={data['triggerCamera']} "
              f"reason=\"{data['reason']}\"")
        return data
    except requests.exceptions.RequestException as e:
        print(f"  -> WARNING: could not reach backend ({e}). Is it running on localhost:8080?")
        return None


def upload_capture(event_id, image_path):
    try:
        with open(image_path, "rb") as f:
            files = {"image": (image_path, f, "image/jpeg")}
            data = {"eventId": event_id}
            response = requests.post(BACKEND_CAPTURE_URL, data=data, files=files, timeout=15)
        response.raise_for_status()
        return response.json()
    except requests.exceptions.RequestException as e:
        print(f"  -> WARNING: capture upload failed for {image_path} ({e})")
        return None


def upload_video(event_id, video_path, termination_reason):
    if not os.path.exists(video_path):
        print(f"  -> WARNING: no video file found at {video_path}, skipping video upload.")
        return None
    try:
        with open(video_path, "rb") as f:
            files = {"video": (video_path, f, "video/mp4")}
            data = {"eventId": event_id, "terminationReason": termination_reason}
            response = requests.post(BACKEND_VIDEO_CAPTURE_URL, data=data, files=files, timeout=60)
        response.raise_for_status()
        result = response.json()
        print(f"  -> video uploaded: {result.get('videoPath')} (terminationReason={termination_reason})")
        return result
    except requests.exceptions.RequestException as e:
        print(f"  -> WARNING: video upload failed for {video_path} ({e})")
        return None


def verification_type_for_label(label):
    return "PLATE" if label.upper() in ("CAR", "BIKE") else "FACE"


def capture_and_monitor(event_id, label):
    verification_type = verification_type_for_label(label)
    print(f"  -> starting continuous monitoring ({verification_type})...")

    video_path = video_path_for_event(event_id)

    max_duration = MONITOR_MAX_DURATION_SECONDS
    if verification_type == "PLATE":
        max_duration = MONITOR_MAX_DURATION_SECONDS + PLATE_EXTRA_RECORD_SECONDS

    stop_on_match = label.upper() != "SCREAM"

    empty_streak = 0
    sample_num = 0
    last_result = None
    termination_reason = "TIMEOUT"
    extra_record_until = None

    for frame in monitor_frames(interval_seconds=MONITOR_INTERVAL_SECONDS,
                                 max_duration_seconds=max_duration,
                                 record_path=video_path):
        if extra_record_until is not None:
            if time.time() >= extra_record_until:
                break
            continue

        sample_num += 1
        image_path = save_frame(frame, f"{event_id}_{sample_num}.jpg")
        result = upload_capture(event_id, image_path)

        if result is None:
            continue

        last_result = result
        status = result.get("status")

        if verification_type == "FACE":
            face_count = result.get("faceCount")
            any_unknown = result.get("anyUnknownFace")
            person_detected = result.get("personDetected", True)
            print(f"  -> sample {sample_num}: status={status} faceCount={face_count} "
                  f"anyUnknownFace={any_unknown} personDetected={person_detected}")
        else:
            any_unknown = result.get("anyUnknownFace")
            print(f"  -> sample {sample_num}: status={status} "
                  f"matchedIdentity={result.get('matchedIdentity')} driverUnknown={any_unknown}")
        set_live_overlay(result.get("faces"))

        if status in VERIFIED_STATUSES:
            if verification_type == "PLATE":
                print(f"  -> vehicle matched on sample {sample_num} - recording "
                      f"{PLATE_EXTRA_RECORD_SECONDS}s more before stopping")
                termination_reason = "MATCHED"
                extra_record_until = time.time() + PLATE_EXTRA_RECORD_SECONDS
                continue
            if stop_on_match:
                print(f"  -> verified on sample {sample_num}, stopping monitoring")
                termination_reason = "MATCHED"
                break
            print(f"  -> sample {sample_num} matched, but continuing to monitor "
                  f"(SCREAM stays on until everyone leaves)")

        if verification_type == "FACE":
            face_count = result.get("faceCount")
            person_detected = result.get("personDetected", True)
            if face_count == 0 and not person_detected:
                empty_streak += 1
                if empty_streak >= FACE_DEBOUNCE_EMPTY_SAMPLES:
                    print(f"  -> nobody visible (no face, no person) for {empty_streak} "
                          f"consecutive samples, assuming they left, stopping monitoring")
                    termination_reason = "LEFT_FRAME"
                    break
            else:
                empty_streak = 0
    else:
        if termination_reason != "MATCHED":
            last_status = last_result.get("status") if last_result else "none (no samples succeeded)"
            print(f"  -> monitoring ended without a resolved match "
                  f"(max duration reached, or camera/network issue). last status: {last_status}")
            termination_reason = "TIMEOUT"

    upload_video(event_id, video_path, termination_reason)


def _run_monitoring_in_background(event_id, label):
    """Wraps capture_and_monitor() to run off the main thread, and clears
    the "monitoring active" flag when it's done, however it ends."""
    global _monitoring_active
    try:
        capture_and_monitor(event_id, label)
    finally:
        with _monitoring_lock:
            _monitoring_active = False


def trigger_monitoring(event_id, label):
    """Starts a monitoring session in the background if none is already
    running - keeps the main audio-capture loop free to keep listening
    the whole time, instead of blocking on the camera."""
    global _monitoring_active
    with _monitoring_lock:
        if _monitoring_active:
            print("  -> already monitoring another event, skipping camera trigger for this one")
            return
        _monitoring_active = True
    thread = threading.Thread(target=_run_monitoring_in_background, args=(event_id, label), daemon=True)
    thread.start()


def main():
    print(f"Loading model from {KERAS_MODEL_PATH} ...")
    model = tf.keras.models.load_model(KERAS_MODEL_PATH)

    window_samples = int(SAMPLE_RATE * CLIP_DURATION_SECONDS)
    last_reported = {}

    print(f"Listening on default microphone at {SAMPLE_RATE} Hz. Press Ctrl+C to stop.\n")

    try:
        while True:
            audio_window = sd.rec(window_samples, samplerate=SAMPLE_RATE, channels=1, dtype="float32")
            sd.wait()
            audio_window = audio_window.flatten()

            label, confidence = classify_window(model, audio_window)
            timestamp = time.strftime("%H:%M:%S")

            triggered_this_window = label in ACTIONABLE_CLASSES and confidence >= MIN_CONFIDENCE_TO_REPORT
            save_live_audio(audio_window, label, confidence, triggered_this_window)

            if label not in ACTIONABLE_CLASSES:
                print(f"[{timestamp}] background (confidence {confidence:.2f})")
                continue

            print(f"[{timestamp}] {label.upper()} (confidence {confidence:.2f})")

            if confidence < MIN_CONFIDENCE_TO_REPORT:
                print(f"  -> below reporting threshold ({MIN_CONFIDENCE_TO_REPORT}), not sent")
                continue

            now = time.time()
            if label in last_reported and (now - last_reported[label]) < COOLDOWN_SECONDS:
                print(f"  -> within cooldown window, not sent again")
                continue

            last_reported[label] = now
            result = post_audio_event(label, confidence)
            if result and result.get("triggerCamera"):
                trigger_monitoring(result["eventId"], label)

    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()