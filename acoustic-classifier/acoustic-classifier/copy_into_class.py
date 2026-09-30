"""
Generic helper: copies every .wav file from a source folder into one of our
dataset/<class>/ folders, with a filename prefix to avoid any collision with
existing files already there (e.g. MVD's "Recording_..." files).

Use this for any single-purpose folder of audio where every file belongs to
the same class -- like the Vehicle Sounds Dataset's Cars/ and Motocycles/
folders, which don't need MVD-style filename label parsing since the whole
folder is already one class.

Usage:
    python copy_into_class.py "D:/path/to/Motocycles" bike
    python copy_into_class.py "D:/path/to/Cars" car

    # Exclude files whose name contains any of the given substrings
    # (case-insensitive, comma-separated) -- useful when a folder has
    # mislabeled/contaminated content mixed in, e.g. chainsaw clips inside
    # a motorcycle folder, or generic ambient audio mislabeled as a vehicle:
    python copy_into_class.py "D:/path/to/Motocycles" bike --exclude chainsaw
    python copy_into_class.py "D:/path/to/Cars" car --exclude "Street_Sounds"
"""

import os
import shutil
import sys

from config import DATASET_DIR, CLASSES


def main():
    args = sys.argv[1:]
    exclude_terms = []
    if "--exclude" in args:
        idx = args.index("--exclude")
        exclude_terms = [t.strip().lower() for t in args[idx + 1].split(",")]
        args = args[:idx] + args[idx + 2:]

    if len(args) != 2:
        print("Usage: python copy_into_class.py <source-folder> <class-name> [--exclude term1,term2]")
        print(f"  class-name must be one of: {CLASSES}")
        sys.exit(1)

    source_folder, class_name = args[0], args[1]

    if class_name not in CLASSES:
        print(f"'{class_name}' isn't one of {CLASSES}")
        sys.exit(1)

    if not os.path.isdir(source_folder):
        print(f"'{source_folder}' is not a folder -- check the path.")
        sys.exit(1)

    dest_dir = os.path.join(DATASET_DIR, class_name)
    os.makedirs(dest_dir, exist_ok=True)

    # Derive the prefix from the source folder's own name rather than a fixed
    # string, so each copied file's origin stays identifiable (useful if you
    # ever need to trace a misclassification back to its source dataset) --
    # and so this script stays accurate as it gets reused for more sources.
    raw_name = os.path.basename(os.path.normpath(source_folder))
    prefix = "".join(c if c.isalnum() else "_" for c in raw_name).strip("_").lower() + "_"

    copied = 0
    skipped = 0
    for dirpath, _, filenames in os.walk(source_folder):
        for filename in filenames:
            if not filename.lower().endswith(".wav"):
                continue
            if any(term in filename.lower() for term in exclude_terms):
                skipped += 1
                continue
            src = os.path.join(dirpath, filename)
            dst = os.path.join(dest_dir, prefix + filename)
            shutil.copyfile(src, dst)
            copied += 1

    print(f"Copied {copied} files from '{source_folder}' into dataset/{class_name}/")
    if skipped:
        print(f"Skipped {skipped} files matching exclude terms {exclude_terms}")


if __name__ == "__main__":
    main()
