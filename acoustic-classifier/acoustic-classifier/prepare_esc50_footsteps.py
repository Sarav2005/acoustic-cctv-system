"""
Pulls the "footsteps" class out of the ESC-50 dataset and copies those clips
into dataset/footsteps/.

ESC-50 (download from https://github.com/karolpiczak/ESC-50 -> "Download ESC-50
dataset" button, ~600MB zip) has a fixed, known structure once unzipped:

    ESC-50-master/
        audio/            <- all 2000 clips, flat, e.g. "1-100032-A-0.wav"
        meta/esc50.csv     <- columns: filename, fold, target, category, ...

We don't need to touch anything else in ESC-50 -- just filter meta/esc50.csv
for category == "footsteps" and copy those specific files.

Usage:
    python prepare_esc50_footsteps.py "C:/path/to/ESC-50-master"
"""

import csv
import os
import shutil
import sys

from config import DATASET_DIR


def main():
    if len(sys.argv) != 2:
        print("Usage: python prepare_esc50_footsteps.py <path-to-ESC-50-master>")
        sys.exit(1)

    esc50_root = sys.argv[1]
    meta_path = os.path.join(esc50_root, "meta", "esc50.csv")
    audio_dir = os.path.join(esc50_root, "audio")

    if not os.path.isfile(meta_path):
        print(f"Could not find {meta_path} -- check the path you passed in.")
        sys.exit(1)

    dest_dir = os.path.join(DATASET_DIR, "footsteps")
    os.makedirs(dest_dir, exist_ok=True)

    copied = 0
    with open(meta_path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row["category"] == "footsteps":
                src = os.path.join(audio_dir, row["filename"])
                dst = os.path.join(dest_dir, row["filename"])
                if os.path.isfile(src):
                    shutil.copyfile(src, dst)
                    copied += 1
                else:
                    print(f"Warning: expected file not found: {src}")

    print(f"Copied {copied} footsteps clips into {dest_dir}")
    if copied < 40:
        print("Expected 40 clips -- fewer than that means some files were missing "
              "or the path passed in wasn't the right ESC-50-master folder.")


if __name__ == "__main__":
    main()
