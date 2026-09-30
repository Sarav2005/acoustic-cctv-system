"""
Module 4 - ANPR: fuzzy-match an OCR'd plate string against the resident/
vehicle database.

Fuzzy matching (not exact) is needed because OCR on a real plate photo
will very often get 1-2 characters wrong (0/O, 1/I, 8/B, S/5 confusions
are the classic ones). We match on similarity ratio and require a minimum
confidence before calling it a match, so a badly-misread plate reports
NO_MATCH rather than confidently attaching to the wrong resident.
"""

import json
import os
from itertools import product

from thefuzz import fuzz

DEFAULT_DB_PATH = os.path.join(os.path.dirname(__file__), "vehicle_database.json")

# Minimum thefuzz ratio (0-100) to accept a match. Set conservatively:
# a real plate string is short (~10 chars), so even one wrong character
# already costs ~10-20 points of ratio - this threshold tolerates roughly
# 1-2 character OCR errors while still rejecting a genuinely different
# plate.
MATCH_THRESHOLD = 75

# --- Format-guided correction ------------------------------------------
#
# Indian plates have two positions the format GUARANTEES regardless of
# the variable-length middle section (RTO code + series): the first 2
# characters are always letters (state code), and the last 4 are always
# digits (the plate number). When OCR puts a character of the wrong class
# in one of those guaranteed positions, that's not ambiguity - it's a
# provable OCR error, and standard visual look-alike pairs (0/O/D, 1/I/L,
# etc.) tell us what it plausibly should have been.
#
# This is deliberately narrower than a general confusable-character
# substitution: it never touches the middle section (where we can't be
# certain what belongs), and it only uses well-established look-alike
# pairs - not guesses. It's applied as a pre-processing step before fuzzy
# matching, so it never weakens MATCH_THRESHOLD - a corrected variant
# still has to clear the same bar as any other candidate.
_DIGIT_TO_LETTERS = {
    "0": ["D", "O", "Q"],
    "1": ["I", "L"],
    "5": ["S"],
    "8": ["B"],
    "2": ["Z"],
    "6": ["G"],
    "7": ["T"],
}
_LETTER_TO_DIGITS = {
    "D": ["0"], "O": ["0"], "Q": ["0"],
    "I": ["1"], "L": ["1"],
    "S": ["5"],
    "B": ["8"],
    "Z": ["2"],
    "G": ["6"],
    "T": ["7"],
}

_GUARANTEED_LETTER_POSITIONS = (0, 1)  # state code - always letters, any length
_GUARANTEED_DIGIT_TAIL_LENGTH = 4      # plate number - always digits, at the end

# The overwhelmingly common modern Indian format is exactly 10 characters:
# 2 letters (state) + 2 digits (RTO code) + 2 letters (series) + 4 digits
# (number) - e.g. TN22AB1234. This isn't a guess: every plate in this
# project's own vehicle_database.json happens to follow it. When the OCR'd
# text is exactly this length, positions 4-5 are ALSO guaranteed letters
# (the series), not just the variable-middle-section we'd otherwise have
# to leave alone. For any other length, we fall back to only correcting
# the two positions that are guaranteed regardless of format variant
# (first 2, last 4) - see _generate_corrected_variants below.
_COMMON_PLATE_LENGTH = 10
_COMMON_FORMAT_EXTRA_LETTER_POSITIONS = (4, 5)

_MAX_CORRECTED_VARIANTS = 32           # safety cap on combinatorial blowup


def _generate_corrected_variants(text):
    """
    Generate plausible corrected variants of an OCR'd plate string, fixing
    ONLY characters at positions the Indian plate format guarantees are a
    specific class (first 2 = letters, last 4 = digits, plus 4-5 = letters
    for exactly-10-char reads - see above), using standard OCR
    visual-confusion pairs. Never touches the variable-length middle
    section. Returns a list starting with the original text, followed by
    any corrected variants (empty of extras if no correction applies).

    Also tries dropping a single leading or trailing character before
    applying position-based correction. A stray extra character at a crop
    boundary has been the single most common artifact seen in this
    project's real-world testing (a leading "C" on one CCTV read, a
    leading "7" on a webcam read) - both otherwise-good reads that were
    exactly the right plate text with one garbage character stuck on the
    front. Trying the trimmed length alongside the raw length lets the
    10-char format-specific correction activate even when a boundary
    artifact would otherwise shift every position by one.
    """
    base_texts = {text}
    if len(text) == _COMMON_PLATE_LENGTH + 1:
        base_texts.add(text[1:])   # drop a possible leading garbage char
        base_texts.add(text[:-1])  # drop a possible trailing garbage char

    all_variants = []
    for base in base_texts:
        all_variants.extend(_corrected_variants_for_length(base))

    # de-duplicate while preserving order, original text first
    seen = set()
    ordered = [text] if text not in seen and not seen.add(text) else []
    for v in all_variants:
        if v not in seen:
            seen.add(v)
            ordered.append(v)
    return ordered[:_MAX_CORRECTED_VARIANTS]


def _corrected_variants_for_length(text):
    if len(text) < 6:
        return [text]

    options_per_position = {}

    letter_positions = set(_GUARANTEED_LETTER_POSITIONS)
    if len(text) == _COMMON_PLATE_LENGTH:
        letter_positions |= set(_COMMON_FORMAT_EXTRA_LETTER_POSITIONS)

    for i in letter_positions:
        if i >= len(text):
            continue
        ch = text[i]
        if ch in _DIGIT_TO_LETTERS:
            options_per_position[i] = _DIGIT_TO_LETTERS[ch]

    tail_start = len(text) - _GUARANTEED_DIGIT_TAIL_LENGTH
    for i in range(max(tail_start, max(letter_positions) + 1), len(text)):
        ch = text[i]
        if ch in _LETTER_TO_DIGITS:
            options_per_position[i] = _LETTER_TO_DIGITS[ch]

    if not options_per_position:
        return [text]

    positions = list(options_per_position.keys())
    option_lists = [options_per_position[p] for p in positions]

    variants = [text]
    for combo in product(*option_lists):
        chars = list(text)
        for pos, repl in zip(positions, combo):
            chars[pos] = repl
        variants.append("".join(chars))
        if len(variants) >= _MAX_CORRECTED_VARIANTS:
            break

    return variants


class VehicleDatabase:
    def __init__(self, db_path=DEFAULT_DB_PATH):
        self.db_path = db_path
        self.vehicles = []
        self.reload()

    def reload(self):
        with open(self.db_path, "r") as f:
            data = json.load(f)
        self.vehicles = data.get("vehicles", [])

    def match(self, ocr_text, threshold=MATCH_THRESHOLD):
        """
        Find the best-matching vehicle record for an OCR'd plate string.

        Returns:
            {
              "status": "MATCH" | "NO_MATCH",
              "confidence": int (0-100),
              "vehicle": {...} or None,
              "query": ocr_text
            }
        """
        if not ocr_text:
            return {"status": "NO_MATCH", "confidence": 0, "vehicle": None, "query": ocr_text}

        ocr_text = ocr_text.upper().strip()

        best_score = -1
        best_vehicle = None
        for vehicle in self.vehicles:
            score = fuzz.ratio(ocr_text, vehicle["plate"].upper())
            if score > best_score:
                best_score = score
                best_vehicle = vehicle

        if best_score >= threshold:
            return {
                "status": "MATCH",
                "confidence": best_score,
                "vehicle": best_vehicle,
                "query": ocr_text,
            }

        return {
            "status": "NO_MATCH",
            "confidence": max(best_score, 0),
            "vehicle": None,
            "query": ocr_text,
        }

    def match_with_correction(self, ocr_text, threshold=MATCH_THRESHOLD):
        """
        Like match(), but if the raw OCR text doesn't clear the threshold,
        also tries format-guided corrected variants (see
        _generate_corrected_variants above) and returns whichever variant
        scores best. This NEVER lowers the bar for a match - a corrected
        variant still has to clear the same threshold as the raw text
        would. It only gives the matcher cleaner candidate strings to try
        when the raw OCR read has an error in a position the plate format
        guarantees is wrong.
        """
        if not ocr_text:
            return self.match(ocr_text, threshold)

        cleaned = ocr_text.upper().strip()
        best_result = self.match(cleaned, threshold)
        if best_result["status"] == "MATCH":
            return best_result

        for variant in _generate_corrected_variants(cleaned):
            if variant == cleaned:
                continue
            result = self.match(variant, threshold)
            if result["confidence"] > best_result["confidence"]:
                best_result = dict(result)
                best_result["corrected_from"] = cleaned
            if result["status"] == "MATCH":
                result = dict(result)
                result["corrected_from"] = cleaned
                return result

        return best_result


if __name__ == "__main__":
    db = VehicleDatabase()
    tests = ["TN22AB1234", "TN22AB1Z34", "TN22A81234", "ZZ99ZZ9999"]
    for t in tests:
        print(t, "->", db.match(t))

    print()
    print("--- match_with_correction ---")
    corrected_tests = ["1437006091", "CPOPCBSG3L", "CAP03CB563E"]
    for t in corrected_tests:
        print(t, "->", db.match_with_correction(t))
