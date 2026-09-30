"""
Runs the trained model against your own real-world recordings (the 1-2 clips
per class you record yourself) and reports whether the prediction matches
the true label.

This is deliberately separate from train.py's test-set evaluation: the
train/test split there is still drawn from the same public datasets (MVD/
ESC-50/Kaggle), so a good score there mainly tells you the model learned
those datasets. Testing against YOUR OWN mic recordings, in your actual
environment, is the more honest check of whether it'll work in the real demo.

Usage:
    python test_real_samples.py my_car_test.wav car
    python test_real_samples.py my_scream_test.wav scream
"""

import sys

import numpy as np
import tensorflow as tf

from config import CLASSES, KERAS_MODEL_PATH
from preprocess import load_and_fix_length, extract_log_mel

# Note: load_and_fix_length already applies loudness normalization internally,
# so no separate call is needed here -- it's consistent with training.


def main():
    if len(sys.argv) != 3:
        print("Usage: python test_real_samples.py <path-to-wav> <true-label>")
        print(f"  true-label must be one of: {CLASSES}")
        sys.exit(1)

    wav_path, true_label = sys.argv[1], sys.argv[2]
    if true_label not in CLASSES:
        print(f"'{true_label}' isn't one of {CLASSES}")
        sys.exit(1)

    model = tf.keras.models.load_model(KERAS_MODEL_PATH)

    audio = load_and_fix_length(wav_path)
    spec = extract_log_mel(audio)
    spec = (spec + 80.0) / 80.0
    spec = np.clip(spec, 0.0, 1.0)
    spec = spec[np.newaxis, ...]

    probs = model.predict(spec, verbose=0)[0]
    predicted_idx = int(np.argmax(probs))
    predicted_label = CLASSES[predicted_idx]

    print(f"\nFile: {wav_path}")
    print(f"True label:      {true_label}")
    print(f"Predicted label: {predicted_label} (confidence {probs[predicted_idx]:.2f})")
    print("\nAll class probabilities:")
    for cls, p in sorted(zip(CLASSES, probs), key=lambda x: -x[1]):
        print(f"  {cls:12s}: {p:.3f}")

    if predicted_label == true_label:
        print("\n\u2713 Correct")
    else:
        print("\n\u2717 Incorrect -- this is useful signal, not a failure. It tells you "
              "this class needs more/better training data before the real demo.")


if __name__ == "__main__":
    main()
