"""
Walks dataset/<class>/*.wav for every class in config.CLASSES, converts each
clip into a fixed-size log-mel spectrogram, and saves everything into one
features.npz file (X = spectrograms, y = integer labels).

Run this once after you've populated all 4 dataset/<class>/ folders, and
again any time you add more data. Training reads from features.npz, not from
the raw wav files directly -- this keeps preprocessing and training as
separate, independently re-runnable steps.

Usage:
    python preprocess.py
"""

import os
import sys

import librosa
import numpy as np

from config import (
    CLASSES, DATASET_DIR, SAMPLE_RATE, CLIP_DURATION_SECONDS,
    N_MELS, N_FFT, HOP_LENGTH, N_FRAMES, FEATURES_NPZ,
)

TARGET_RMS = 0.1  # target loudness level every clip gets normalized to


def normalize_loudness(audio):
    """Rescale audio so its RMS (average loudness) matches TARGET_RMS,
    regardless of how loud or quiet the original recording was.

    This matters a lot for real-world deployment: a scream from 20 meters
    away arrives at the mic much quieter than one from 2 meters away, purely
    due to distance -- nothing to do with how "scream-like" it sounds. Without
    this normalization, the model partly learns "loud = scream", which
    systematically fails on quiet/distant real emergencies -- exactly the
    case where correctly detecting it matters most. Normalizing loudness
    before feature extraction forces the model to key off the sound's
    spectral/temporal shape instead of its raw volume.

    Applied identically here (training) and in live_infer.py (real-time),
    so the model always sees the same kind of input it was trained on.
    """
    rms = np.sqrt(np.mean(audio ** 2))
    if rms < 1e-6:  # near-silence -- avoid dividing by ~zero and blowing up noise
        return audio
    return np.clip(audio * (TARGET_RMS / rms), -1.0, 1.0)


def find_loudest_window(audio, target_len, hop=None):
    """For a clip longer than target_len, finds the target_len-sample window
    with the highest RMS energy, rather than blindly taking the center.

    This matters a lot for real-world recordings: a 40-second clip of a
    stationary bike might have the actual engine sound concentrated in a
    5-second burst somewhere in the middle-ish, surrounded by long stretches
    of near-silence (handling noise, wind, waiting for the engine to rev).
    The plain center-crop this replaced could easily land one window away
    from the loud part and grab near-silence instead -- confirmed directly
    on real test clips, where the center window's RMS was measurably lower
    than a window just a few seconds away that contained the actual engine
    burst. Picking the loudest window makes sure training (and testing)
    actually sees the sound event, not an arbitrary slice of the recording.
    """
    if hop is None:
        hop = max(1, target_len // 4)  # 25% hop -- fine enough resolution, still fast

    n_samples = len(audio)
    last_start = n_samples - target_len
    starts = list(range(0, last_start + 1, hop))
    if starts[-1] != last_start:
        starts.append(last_start)  # make sure the tail end is always checked too

    best_start, best_rms = starts[0], -1.0
    for s in starts:
        window = audio[s:s + target_len]
        rms = np.sqrt(np.mean(window ** 2))
        if rms > best_rms:
            best_rms = rms
            best_start = s

    return best_start


def load_and_fix_length(path):
    """Load a wav file, resample to SAMPLE_RATE, and pad/trim to exactly
    CLIP_DURATION_SECONDS. Real-world clips are rarely exactly 1 second long,
    so this makes every clip's raw waveform the same length before we even
    get to the spectrogram step."""
    audio, _ = librosa.load(path, sr=SAMPLE_RATE, mono=True)
    target_len = int(SAMPLE_RATE * CLIP_DURATION_SECONDS)

    if len(audio) < target_len:
        audio = np.pad(audio, (0, target_len - len(audio)))
    else:
        start = find_loudest_window(audio, target_len)
        audio = audio[start:start + target_len]

    audio = normalize_loudness(audio)
    return audio


def extract_log_mel(audio):
    """Convert a fixed-length waveform into a log-mel spectrogram of shape
    (N_MELS, N_FRAMES). This is the actual "image" the CNN will learn from --
    frequency on one axis, time on the other, brightness = loudness."""
    mel_spec = librosa.feature.melspectrogram(
        y=audio, sr=SAMPLE_RATE, n_fft=N_FFT, hop_length=HOP_LENGTH, n_mels=N_MELS
    )
    log_mel = librosa.power_to_db(mel_spec, ref=np.max)

    # Ensure exact fixed shape (librosa's frame count can be off by one
    # depending on rounding) so every sample fed to the CNN has an identical
    # shape.
    if log_mel.shape[1] < N_FRAMES:
        pad_width = N_FRAMES - log_mel.shape[1]
        log_mel = np.pad(log_mel, ((0, 0), (0, pad_width)), mode="constant", constant_values=log_mel.min())
    else:
        log_mel = log_mel[:, :N_FRAMES]

    return log_mel.astype(np.float32)


def main():
    features = []
    labels = []
    per_class_counts = {}

    for class_idx, class_name in enumerate(CLASSES):
        class_dir = os.path.join(DATASET_DIR, class_name)
        if not os.path.isdir(class_dir):
            print(f"Warning: {class_dir} does not exist, skipping.")
            per_class_counts[class_name] = 0
            continue

        wav_files = [f for f in os.listdir(class_dir) if f.lower().endswith(".wav")]
        per_class_counts[class_name] = len(wav_files)

        for filename in wav_files:
            path = os.path.join(class_dir, filename)
            try:
                audio = load_and_fix_length(path)
                spec = extract_log_mel(audio)
                features.append(spec)
                labels.append(class_idx)
            except Exception as e:
                print(f"Skipping {path}: {e}")

    if not features:
        print("No audio files found. Populate dataset/<class>/ folders first.")
        sys.exit(1)

    X = np.stack(features)          # shape: (num_samples, N_MELS, N_FRAMES)
    y = np.array(labels, dtype=np.int64)

    np.savez(FEATURES_NPZ, X=X, y=y, classes=np.array(CLASSES))

    print("\nClass counts:")
    for class_name, count in per_class_counts.items():
        print(f"  {class_name:12s}: {count}")
    print(f"\nSaved {X.shape[0]} samples of shape {X.shape[1:]} to {FEATURES_NPZ}")

    counts = list(per_class_counts.values())
    if max(counts) > 0 and min(counts) > 0 and max(counts) / min(counts) > 5:
        print("\nNote: your classes are quite imbalanced (biggest class is >5x "
              "the smallest). We'll use class weighting during training to "
              "compensate, but adding more data to the smaller classes would "
              "help accuracy more than any amount of tuning.")


if __name__ == "__main__":
    main()
