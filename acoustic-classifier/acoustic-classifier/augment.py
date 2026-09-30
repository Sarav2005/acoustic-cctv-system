"""
Generates synthetic training variants for the two classes that need help:

1. FOOTSTEPS (40 real clips -> target ~800): the fundamental problem from the
   first training run -- 40 clips is far too few for a CNN to learn a robust
   notion of "footsteps" at all. We apply combinations of pitch-shifting,
   time-stretching, gain changes, and noise-mixing to multiply these into a
   much larger, more varied set.

2. SCREAM (862 real clips -> +~600 noise-mixed variants): the first training
   run showed 133/573 real background clips misclassified as scream. A likely
   contributor: the source scream clips are mostly "clean" isolated screams,
   so the model may have learned a narrow acoustic signature that doesn't
   generalize to screams-in-noisy-real-environments, and/or hasn't seen enough
   contrast between "quiet background" and "background with something loud
   happening in it". Mixing real background clips UNDER real scream clips at
   varying volume ratios (SNR) teaches the model scream's defining
   characteristics under realistic noise conditions.

This does NOT touch car/bike/background -- those already have enough real
samples (1005, 1122, 2865) that augmenting them further has low payoff and
mainly costs training time.

Augmented files are saved into the same dataset/<class>/ folders as new
files (suffixed _aug<N>.wav) alongside the originals -- preprocess.py doesn't
need any changes, it just picks up every .wav file in each folder.

Usage:
    python augment.py
"""

import os
import random

import librosa
import numpy as np
import soundfile as sf

from config import DATASET_DIR, SAMPLE_RATE

FOOTSTEPS_TARGET = 800
SCREAM_NOISE_VARIANTS_TARGET = 600  # additional clips, on top of the 862 real ones

random.seed(42)
np.random.seed(42)


def load_all_wavs(folder):
    paths = [os.path.join(folder, f) for f in os.listdir(folder) if f.lower().endswith(".wav")]
    return paths


def pitch_shift(audio, n_steps):
    return librosa.effects.pitch_shift(audio, sr=SAMPLE_RATE, n_steps=n_steps)


def time_stretch(audio, rate):
    stretched = librosa.effects.time_stretch(audio, rate=rate)
    # Restore original length by padding or trimming, so downstream
    # preprocessing's own pad/trim logic still has a sensible starting point.
    if len(stretched) < len(audio):
        stretched = np.pad(stretched, (0, len(audio) - len(stretched)))
    else:
        stretched = stretched[: len(audio)]
    return stretched


def change_gain(audio, factor):
    return np.clip(audio * factor, -1.0, 1.0)


def mix_with_noise(audio, noise_audio, snr_db):
    """Mix `noise_audio` under `audio` at the given signal-to-noise ratio."""
    # Match lengths by tiling/trimming the noise to the signal's length.
    if len(noise_audio) < len(audio):
        repeats = int(np.ceil(len(audio) / len(noise_audio)))
        noise_audio = np.tile(noise_audio, repeats)
    noise_audio = noise_audio[: len(audio)]

    signal_power = np.mean(audio ** 2) + 1e-10
    noise_power = np.mean(noise_audio ** 2) + 1e-10
    target_noise_power = signal_power / (10 ** (snr_db / 10))
    scale = np.sqrt(target_noise_power / noise_power)

    mixed = audio + noise_audio * scale
    return np.clip(mixed, -1.0, 1.0)


def augment_footsteps():
    folder = os.path.join(DATASET_DIR, "footsteps")
    background_folder = os.path.join(DATASET_DIR, "background")

    original_paths = load_all_wavs(folder)
    # Filter out any augmented files from a previous run so we don't
    # compound augmentation on top of augmentation if this script is re-run.
    original_paths = [p for p in original_paths if "_aug" not in os.path.basename(p)]

    background_paths = load_all_wavs(background_folder)

    if not original_paths:
        print("No original footsteps clips found -- nothing to augment.")
        return

    print(f"Augmenting footsteps: {len(original_paths)} real clips -> target {FOOTSTEPS_TARGET}")

    originals = [librosa.load(p, sr=SAMPLE_RATE, mono=True)[0] for p in original_paths]

    count = 0
    aug_idx = 0
    while count < FOOTSTEPS_TARGET - len(original_paths):
        base = random.choice(originals)
        audio = base.copy()

        # Randomly stack 1-3 transformations for variety.
        transforms = random.sample(
            ["pitch", "stretch", "gain", "noise"], k=random.randint(1, 3)
        )
        if "pitch" in transforms:
            audio = pitch_shift(audio, n_steps=random.uniform(-3, 3))
        if "stretch" in transforms:
            audio = time_stretch(audio, rate=random.uniform(0.8, 1.25))
        if "gain" in transforms:
            audio = change_gain(audio, factor=random.uniform(0.6, 1.4))
        if "noise" in transforms and background_paths:
            noise_path = random.choice(background_paths)
            noise_audio, _ = librosa.load(noise_path, sr=SAMPLE_RATE, mono=True)
            audio = mix_with_noise(audio, noise_audio, snr_db=random.uniform(5, 20))

        out_path = os.path.join(folder, f"footsteps_aug{aug_idx}.wav")
        sf.write(out_path, audio, SAMPLE_RATE)
        aug_idx += 1
        count += 1

    print(f"  Wrote {count} augmented footsteps clips (total now ~{len(original_paths) + count})")


def augment_scream():
    folder = os.path.join(DATASET_DIR, "scream")
    background_folder = os.path.join(DATASET_DIR, "background")

    original_paths = load_all_wavs(folder)
    original_paths = [p for p in original_paths if "_aug" not in os.path.basename(p)]
    background_paths = load_all_wavs(background_folder)

    if not original_paths or not background_paths:
        print("Missing scream or background clips -- skipping scream noise augmentation.")
        return

    print(f"Adding {SCREAM_NOISE_VARIANTS_TARGET} noise-mixed scream variants "
          f"(mixing real background under real screams at varying SNR)")

    for i in range(SCREAM_NOISE_VARIANTS_TARGET):
        scream_path = random.choice(original_paths)
        noise_path = random.choice(background_paths)

        scream_audio, _ = librosa.load(scream_path, sr=SAMPLE_RATE, mono=True)
        noise_audio, _ = librosa.load(noise_path, sr=SAMPLE_RATE, mono=True)

        # Wider SNR range than footsteps -- we specifically want the model to
        # see screams under both mild AND fairly loud background noise, since
        # that's the realistic range for an outdoor mic.
        mixed = mix_with_noise(scream_audio, noise_audio, snr_db=random.uniform(0, 20))

        out_path = os.path.join(folder, f"scream_aug{i}.wav")
        sf.write(out_path, mixed, SAMPLE_RATE)

    print(f"  Wrote {SCREAM_NOISE_VARIANTS_TARGET} noise-mixed scream clips")


def augment_scream_pitch_time(target_count=500):
    """Adds pitch/time-stretch/gain-varied scream clips, distinct from the
    noise-mixed variants above.

    The noise-mixing augmentation only varies WHAT'S MIXED UNDER the scream
    (different background clips, different volumes) -- the core scream
    signature itself stays identical across all those variants, since they
    all come from the same ~862 real source clips. That's a narrower kind of
    diversity than it looks like on paper.

    This adds genuine acoustic variation to the scream signature itself
    (pitch, duration, loudness), the same technique that worked well for
    footsteps. This matters now specifically because bike grew to more total
    samples (1630) than scream after adding the Vehicle Sounds Dataset, and
    scream/bike confusion increased in testing -- giving scream comparable
    diversity and quantity helps rebalance that boundary.
    """
    folder = os.path.join(DATASET_DIR, "scream")
    original_paths = load_all_wavs(folder)
    original_paths = [p for p in original_paths if "_aug" not in os.path.basename(p)]

    if not original_paths:
        print("No original scream clips found -- skipping pitch/time augmentation.")
        return

    print(f"Adding {target_count} pitch/time/gain-varied scream clips "
          f"(distinct from the noise-mixed variants, using real scream clips only)")

    originals = [librosa.load(p, sr=SAMPLE_RATE, mono=True)[0] for p in original_paths]

    for i in range(target_count):
        base = random.choice(originals)
        audio = base.copy()

        transforms = random.sample(["pitch", "stretch", "gain"], k=random.randint(1, 2))
        if "pitch" in transforms:
            audio = pitch_shift(audio, n_steps=random.uniform(-2, 2))
        if "stretch" in transforms:
            audio = time_stretch(audio, rate=random.uniform(0.85, 1.2))
        if "gain" in transforms:
            audio = change_gain(audio, factor=random.uniform(0.7, 1.3))

        out_path = os.path.join(folder, f"scream_pt_aug{i}.wav")
        sf.write(out_path, audio, SAMPLE_RATE)

    print(f"  Wrote {target_count} pitch/time/gain-varied scream clips")


def main():
    augment_footsteps()
    augment_scream()
    augment_scream_pitch_time()
    print("\nDone. Re-run preprocess.py to pick up the new files, then train.py.")


if __name__ == "__main__":
    main()
