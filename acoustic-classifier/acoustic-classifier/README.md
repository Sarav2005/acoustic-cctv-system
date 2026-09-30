# Acoustic Classifier (Module 1) — Laptop Mic Version

Classifies car / bike / footsteps / scream (+ background) from short audio
windows, and reports actionable detections to the Spring Boot backend from
Module 2. Same code path will later feed from the ESP32 + I2S mic instead of
the laptop mic — only the audio *source* changes, everything downstream stays
identical.

## 1. Get the datasets

| Class | Source | What to do |
|---|---|---|
| car, bike | [MVD dataset](https://github.com/Ashhad785/MVD.git) | Clone/download, find the Cars and Motorcycle class folders, copy the .wav files into `dataset/car/` and `dataset/bike/` respectively |
| footsteps | [ESC-50](https://github.com/karolpiczak/ESC-50) | Download the ESC-50 zip, unzip it anywhere, then run `python prepare_esc50_footsteps.py "<path-to-ESC-50-master>"` — this auto-sorts the right 40 clips for you |
| background | ESC-50 (same download) | Run `python prepare_esc50_background.py "<path-to-ESC-50-master>"` — auto-sorts ~1800 varied non-target clips |
| scream | [Kaggle: Human Screaming Detection Dataset](https://github.com/whats2000/HumanScreamingDetection) | Needs a free Kaggle account to download. Once downloaded, copy the scream .wav files into `dataset/scream/` |

End state: `dataset/car/`, `dataset/bike/`, `dataset/footsteps/`,
`dataset/scream/`, `dataset/background/` each contain `.wav` files.

## 2. Augment the small/weak classes

```
python augment.py
```

Multiplies `footsteps` (only 40 real clips -- far too few on its own) up to
~800 via pitch-shifting, time-stretching, gain changes, and noise-mixing, and
adds ~600 noise-mixed variants to `scream` (real screams mixed with real
background at varying volume levels) to make the model more robust against
confusing ordinary background noise for a scream. Takes a few minutes.

Skip this step only if you're intentionally re-running the original
(unaugmented) baseline for comparison.

## 3. Preprocess

```
python preprocess.py
```

Converts every clip into a fixed-size log-mel spectrogram and saves them all
into `features.npz`. Prints class counts — check these look reasonable
before moving on (a class with 0 files means a folder wasn't populated).

## 4. Train

```
python train.py
```

Trains the CNN, prints a classification report + confusion matrix on a held-
out test set, and saves:
- `models/acoustic_classifier.keras` — used by `live_infer.py` and `test_real_samples.py`
- `models/acoustic_classifier.tflite` — the format for eventual ESP32 deployment

Takes a few minutes on a laptop CPU (no GPU needed at this data scale).

## 5. Test against your own real recordings

```
python test_real_samples.py path\to\your\car_test.wav car
python test_real_samples.py path\to\your\scream_test.wav scream
```

This is the more honest accuracy check — dataset-internal test accuracy
tells you the model learned the datasets; this tells you whether it
generalizes to your actual mic and environment.

## 6. Run live, wired to the backend AND the camera (Modules 1 + 3 together)

Make sure the Spring Boot backend (Module 2) is running on `localhost:8080`
first, then:

```
python live_infer.py
```

Listens continuously, classifies each 1-second window, and POSTs
car/bike/footsteps/scream detections above a confidence threshold to
`/api/audio/events`. "Background" predictions are logged locally but never
sent (nothing to act on).

**New:** when the backend's response says `triggerCamera: true`, the script
now captures a frame from your laptop's webcam (`camera_capture.py`) and
uploads it to `/api/captures` for that event -- completing the full
pipeline end to end: sound detected -> trigger decision -> camera capture ->
verification stub -> visible in `/api/events`.

Ctrl+C to stop.

To test the camera capture step in isolation (without the full audio
pipeline), run `python camera_capture.py` directly -- it grabs one frame and
saves it to `webcam_captures/`.

## Known limitations to mention in your report

- **Bike/car separation**: MVD is real recordings from actual traffic, so it
  should generalize reasonably, but your specific building complex's
  acoustics will differ from Delhi-NCR traffic — expect some accuracy drop
  on your own test clips, especially early on.
- **Footsteps**: only 40 source clips from ESC-50 — the smallest class.
  Class weighting in `train.py` compensates during training, but if accuracy
  on footsteps is weak, more real recordings of this class specifically
  would help more than any other tuning.
- **No true "silence" recording from your actual deployment environment**:
  ESC-50's background class is generic (rain, birds, etc), not the specific
  ambient sound of your building complex. If you get false triggers during
  a live demo, recording even 5-10 clips of your actual environment's quiet
  background and adding them to `dataset/background/` would likely fix it
  fast.
