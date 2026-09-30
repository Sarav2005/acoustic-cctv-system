"""
Builds the "background" (rejection) class from ESC-50 by copying clips from
categories that are NOT footsteps and NOT vehicle-related (we exclude
vehicle-related categories so the model doesn't get contradictory signals --
e.g. an "Engine" clip that looks like our car/bike training data but is
labeled background would confuse training).

This grabs a wide variety of ambient/other sounds (rain, wind, birds,
clapping, door knocks, etc) so the model learns a broad sense of "not one of
our 4 target classes" rather than overfitting to one specific kind of quiet.

Usage:
    python prepare_esc50_background.py "C:/path/to/ESC-50-master"
"""

import csv
import os
import shutil
import sys

from config import DATASET_DIR

EXCLUDED_CATEGORIES = {
    "footsteps",   # this is one of our real target classes, not background
    "engine",      # too similar to car/bike -- would confuse the model
    "car_horn",    # ditto
    "siren",       # could plausibly co-occur with vehicles; keep it out to be safe
}


def main():
    if len(sys.argv) != 2:
        print("Usage: python prepare_esc50_background.py <path-to-ESC-50-master>")
        sys.exit(1)

    esc50_root = sys.argv[1]
    meta_path = os.path.join(esc50_root, "meta", "esc50.csv")
    audio_dir = os.path.join(esc50_root, "audio")

    if not os.path.isfile(meta_path):
        print(f"Could not find {meta_path} -- check the path you passed in.")
        sys.exit(1)

    dest_dir = os.path.join(DATASET_DIR, "background")
    os.makedirs(dest_dir, exist_ok=True)

    copied = 0
    with open(meta_path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row["category"] not in EXCLUDED_CATEGORIES:
                src = os.path.join(audio_dir, row["filename"])
                dst = os.path.join(dest_dir, row["filename"])
                if os.path.isfile(src):
                    shutil.copyfile(src, dst)
                    copied += 1

    print(f"Copied {copied} background clips into {dest_dir}")
    print("This is intentionally a large, varied set (most of ESC-50's 50 "
          "categories) -- that's what teaches the model a broad notion of "
          "'not one of our target classes' rather than one narrow sound.")


if __name__ == "__main__":
    main()
