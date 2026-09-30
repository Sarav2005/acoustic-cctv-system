"""
Auto-sorts the MVD dataset into our dataset/ folders based on the label
encoded in each filename.

Per the MVD README, files are named like:
    Recording_1891_O_C.wav   -> label "C" = car
    Recording_546_H_T.wav    -> label "T" = truck
    Recording_454A3_M_N.wav  -> label "N" = no-vehicle (MVDA augmented variant)

We only care about:
    C (car)        -> dataset/car/
    M (motorbike)  -> dataset/bike/
    N (no-vehicle) -> dataset/background/   (real traffic-mic "nothing happening"
                                              audio -- better domain match than
                                              ESC-50's generic background sounds)
    T (truck)      -> skipped entirely (not one of our target classes, and
                                         labeling it as background would be
                                         wrong since it IS a vehicle sound --
                                         better to just leave it out)

Works whether you point it at the MVD or MVDA download folder (or both, by
running it twice against each), and searches subfolders too in case Google
Drive's zip preserved any folder structure.

Usage:
    python prepare_mvd.py "D:\Final Year Project Resources\MVD"
"""

import os
import re
import shutil
import sys

from config import DATASET_DIR

LABEL_MAP = {
    "C": "car",
    "M": "bike",
    "N": "background",
    # "T" (truck) intentionally omitted -- see module docstring
}

# Matches "..._C.wav", "..._M.mp3", "..._N.WAV" etc. -- the label letter is
# whatever's between the last underscore and the file extension.
LABEL_PATTERN = re.compile(r"_([A-Z])\.[^.]+$")

AUDIO_EXTENSIONS = {".wav", ".mp3", ".flac", ".ogg"}


def main():
    if len(sys.argv) != 2:
        print("Usage: python prepare_mvd.py <path-to-downloaded-MVD-folder>")
        sys.exit(1)

    source_root = sys.argv[1]
    if not os.path.isdir(source_root):
        print(f"'{source_root}' is not a folder -- check the path.")
        sys.exit(1)

    for class_name in set(LABEL_MAP.values()):
        os.makedirs(os.path.join(DATASET_DIR, class_name), exist_ok=True)

    counts = {name: 0 for name in LABEL_MAP.values()}
    skipped_truck = 0
    unrecognized = 0

    for dirpath, _, filenames in os.walk(source_root):
        for filename in filenames:
            ext = os.path.splitext(filename)[1].lower()
            if ext not in AUDIO_EXTENSIONS:
                continue

            match = LABEL_PATTERN.search(filename)
            if not match:
                unrecognized += 1
                continue

            label_letter = match.group(1)

            if label_letter == "T":
                skipped_truck += 1
                continue

            class_name = LABEL_MAP.get(label_letter)
            if class_name is None:
                unrecognized += 1
                continue

            src = os.path.join(dirpath, filename)
            dst = os.path.join(DATASET_DIR, class_name, filename)
            shutil.copyfile(src, dst)
            counts[class_name] += 1

    print("Copied:")
    for class_name, count in counts.items():
        print(f"  {class_name:12s}: {count}")
    print(f"  (skipped {skipped_truck} truck clips -- not a target class)")
    if unrecognized:
        print(f"  ({unrecognized} files didn't match the expected naming pattern and were skipped)")

    if counts["car"] == 0 and counts["bike"] == 0:
        print("\nWarning: found 0 car and 0 bike clips. Double-check the path you "
              "passed in actually contains the extracted MVD audio files, and that "
              "filenames look like 'Recording_1891_O_C.wav'.")


if __name__ == "__main__":
    main()
