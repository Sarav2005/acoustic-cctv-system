# Face Recognition Service (Module 5 - Proof of Concept)

Flask microservice for detecting and recognizing faces, verifying
captured footstep-triggered images against enrolled residents/staff.

Model: InsightFace `buffalo_l` (RetinaFace detector + ArcFace r50
recognizer) - see app.py's module docstring for why this was chosen over
lighter options once disk space stopped being a constraint.

**Licensing note:** InsightFace's pretrained models are for
non-commercial/research use - fine for a college project, worth a
one-line mention in your report.

## Setup

```bash
pip install -r requirements.txt
```

No separate binary install needed (unlike Tesseract in Module 4) -
everything here runs through ONNX Runtime, bundled via pip.

## Run

```bash
python app.py
```

Starts on `http://0.0.0.0:5002` (port 5001 is the ANPR service - both can
run side by side). First run downloads the buffalo_l model pack (~280MB,
one-time, needs internet).

## Endpoints

- `GET /health` - liveness check, reports how many people are enrolled.
- `POST /enroll` - multipart form: `image` file (exactly one face in the
  photo) + `name` field, optional `flat_number` and `status` fields.
  Enrolling the same name again ADDS another embedding for that person
  rather than replacing - enroll 2-3 photos per person (different angle/
  lighting) for better real-world accuracy.
- `POST /verify-faces` - multipart form: `image` file. Returns every
  detected face with its own match status, plus `any_unknown` (true if
  ANY face in frame didn't match) and `all_matched` (true only if every
  face matched) - `any_unknown` is what the continuous-monitoring loop
  should check to decide whether to keep watching.

## Example: enrolling someone

```bash
curl -X POST -F "image=@saravana1.jpg" -F "name=Saravana Balan" \
  -F "flat_number=A-101" -F "status=resident" \
  http://127.0.0.1:5002/enroll

curl -X POST -F "image=@saravana2.jpg" -F "name=Saravana Balan" \
  http://127.0.0.1:5002/enroll
```

## IMPORTANT: threshold not yet calibrated

`face_database.py`'s `MATCH_THRESHOLD = 0.45` is a reasonable starting
default, NOT a verified number - unlike Module 4's plate-matching
threshold (tuned against real project data), this hasn't been tested
against real face photos yet. Claude's dev sandbox can't ethically source
photos of real people to calibrate against, so **you need to do this
step**:

1. Enroll yourself (or a teammate) with 2-3 photos.
2. Test `/verify-faces` with a NEW photo of that SAME person - confidence
   should be comfortably above 0.45.
3. Test with a photo of a DIFFERENT, unenrolled person - confidence
   should be comfortably below 0.45.
4. If the gap between those two isn't clean, adjust `MATCH_THRESHOLD` in
   `face_database.py` until it is.

## Tested so far

- `/health`, `/verify-faces` (0-face case), `/enroll` (no-face-detected
  validation) all verified structurally working.
- Matching/threshold logic verified with synthetic embeddings (not real
  faces) - see `face_database.py`'s `__main__` block.
- Real face detection/recognition accuracy NOT yet tested - needs real
  enrolled photos, which only you can provide.
