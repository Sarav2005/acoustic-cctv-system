"""
Shared configuration for the acoustic classifier pipeline.

Every script (preprocessing, training, live inference) imports these
constants so the audio front-end (sample rate, window length, mel-spectrogram
shape) stays IDENTICAL everywhere. This matters a lot: if training uses a
different sample rate or spectrogram shape than live inference, the model
will get garbage input and predict nonsense. Keeping it in one file prevents
that whole class of bug.
"""

import os

# --- Audio front-end settings ---
SAMPLE_RATE = 16000          # 16kHz is plenty for these sound classes and matches
                              # what the ESP32 + I2S mic will realistically capture later
CLIP_DURATION_SECONDS = 1.0  # each classification window is 1 second of audio
N_MELS = 40                  # mel frequency bins - a common size for small on-device models
N_FFT = 512
HOP_LENGTH = 256              # -> ~63 time frames per 1-second clip at 16kHz

# Fixed shape every spectrogram gets padded/truncated to, so the CNN always
# sees the same input size regardless of small variations in clip length.
N_FRAMES = int(SAMPLE_RATE * CLIP_DURATION_SECONDS / HOP_LENGTH) + 1  # ~63

# --- Classes ---
# Order matters: this defines the label index each class maps to everywhere.
# "background" is NOT one of the backend's EventLabel values -- it exists only
# so the model has a way to say "none of the 4 real classes" (silence, wind,
# birds, distant chatter, etc). Without it, softmax forces the model to
# confidently pick car/bike/footsteps/scream for every single window, even
# when nothing is happening -- that's a guaranteed false-trigger machine.
# live_infer.py only POSTs to the backend when the predicted class is one of
# the first 4; a "background" prediction just means "stay quiet, do nothing".
CLASSES = ["car", "bike", "footsteps", "scream", "background"]
ACTIONABLE_CLASSES = {"car", "bike", "footsteps", "scream"}

# --- Paths ---
PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
DATASET_DIR = os.path.join(PROJECT_ROOT, "dataset")
MODELS_DIR = os.path.join(PROJECT_ROOT, "models")
FEATURES_NPZ = os.path.join(PROJECT_ROOT, "features.npz")
KERAS_MODEL_PATH = os.path.join(MODELS_DIR, "acoustic_classifier.keras")
TFLITE_MODEL_PATH = os.path.join(MODELS_DIR, "acoustic_classifier.tflite")

# --- Backend integration ---
BACKEND_AUDIO_EVENT_URL = "http://localhost:8080/api/audio/events"
BACKEND_CAPTURE_URL = "http://localhost:8080/api/captures"
NODE_ID = "laptop-mic-1"  # will become the ESP32's node id later; kept configurable
