"""
Module 5 - Face recognition PoC: enrolled resident/staff database.

Mirrors vehicle_database.py's role from Module 4, but matching is cosine
similarity between ArcFace embeddings instead of fuzzy string matching -
there's no plate-format-style structure to lean on here, just "how close
are these two embedding vectors."

A person can have MULTIPLE enrolled embeddings (e.g. from several enrollment
photos - different angle/lighting). Matching checks a query embedding
against every stored embedding for every person and keeps the best score,
which is more robust than a single reference photo per person.

THRESHOLD CALIBRATION: MATCH_THRESHOLD below is a starting default, not a
verified number - unlike Module 4's fuzzy-match threshold (which was
tuned against real project data), this hasn't been calibrated against any
real face photos yet (Claude's sandbox can't ethically source photos of
real people to test against). Before trusting this for a demo: enroll a
person, then test a few photos of THAT SAME person (should score high)
and a few photos of a DIFFERENT person (should score low), and adjust
MATCH_THRESHOLD so genuine matches clear it and impostors don't.
"""

import json
import os

import numpy as np

DEFAULT_DB_PATH = os.path.join(os.path.dirname(__file__), "face_database.json")

# Cosine similarity ranges -1 to 1. ArcFace-style embeddings for the same
# person typically score noticeably higher than for different people, but
# the exact gap depends on photo quality/angle/lighting - this is a
# starting point, see the calibration note above.
MATCH_THRESHOLD = 0.45


def cosine_similarity(a, b):
    a = np.asarray(a, dtype=np.float32)
    b = np.asarray(b, dtype=np.float32)
    denom = (np.linalg.norm(a) * np.linalg.norm(b))
    if denom == 0:
        return 0.0
    return float(np.dot(a, b) / denom)


class FaceDatabase:
    def __init__(self, db_path=DEFAULT_DB_PATH):
        self.db_path = db_path
        self.people = []
        self.reload()

    def reload(self):
        if not os.path.exists(self.db_path):
            self.people = []
            return
        with open(self.db_path, "r") as f:
            data = json.load(f)
        self.people = data.get("people", [])

    def _save(self):
        with open(self.db_path, "w") as f:
            json.dump({"people": self.people}, f)

    def enroll(self, name, embedding, flat_number=None, status="resident"):
        """Add one embedding for a person. If the person already exists
        (matched by name), appends this embedding to their existing list
        rather than creating a duplicate entry - multiple embeddings per
        person is the whole point (see module docstring)."""
        for person in self.people:
            if person["name"] == name:
                person["embeddings"].append(embedding)
                self._save()
                return person

        person = {
            "name": name,
            "flat_number": flat_number,
            "status": status,
            "embeddings": [embedding],
        }
        self.people.append(person)
        self._save()
        return person

    def match(self, query_embedding, threshold=MATCH_THRESHOLD):
        """
        Find the best-matching enrolled person for a query embedding.

        Returns:
            {
              "status": "MATCH" | "NO_MATCH",
              "confidence": float (cosine similarity, -1 to 1),
              "identity": {...} or None,
              "flat_number": str or None
            }
        """
        best_score = -1.0
        best_person = None

        for person in self.people:
            for stored_embedding in person["embeddings"]:
                score = cosine_similarity(query_embedding, stored_embedding)
                if score > best_score:
                    best_score = score
                    best_person = person

        if best_score >= threshold and best_person is not None:
            return {
                "status": "MATCH",
                "confidence": round(best_score, 3),
                "identity": best_person["name"],
                "flat_number": best_person.get("flat_number"),
            }

        return {
            "status": "NO_MATCH",
            "confidence": round(max(best_score, 0.0), 3),
            "identity": None,
            "flat_number": None,
        }


if __name__ == "__main__":
    # Structural test with SYNTHETIC random embeddings - not real faces.
    # This validates the matching/threshold logic only; it says nothing
    # about real-world accuracy, which needs real enrolled photos (see
    # the calibration note in the module docstring).
    import tempfile

    rng = np.random.default_rng(42)

    with tempfile.TemporaryDirectory() as tmp:
        db_path = os.path.join(tmp, "test_face_db.json")
        db = FaceDatabase(db_path)

        alice_embedding = rng.normal(size=512).astype(np.float32)
        alice_embedding /= np.linalg.norm(alice_embedding)
        db.enroll("Alice", alice_embedding.tolist(), flat_number="A-101")

        # A slightly perturbed version of Alice's embedding, simulating a
        # different photo of the SAME person (embeddings for the same
        # person cluster close together, not identical).
        alice_again = alice_embedding + rng.normal(scale=0.05, size=512).astype(np.float32)
        alice_again /= np.linalg.norm(alice_again)

        # A completely different random embedding, simulating a stranger.
        stranger_embedding = rng.normal(size=512).astype(np.float32)
        stranger_embedding /= np.linalg.norm(stranger_embedding)

        print("Query = Alice's exact enrolled embedding:")
        print(" ", db.match(alice_embedding.tolist()))
        print("Query = a perturbed 'different photo' of Alice:")
        print(" ", db.match(alice_again.tolist()))
        print("Query = an unrelated random embedding (stranger):")
        print(" ", db.match(stranger_embedding.tolist()))
