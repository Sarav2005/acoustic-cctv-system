# ANPR Service (Module 4)

Flask microservice for automatic number plate recognition, verifying
captured vehicle images against the resident/vehicle database.

## Setup

```bash
pip install -r requirements.txt
```

You also need the Tesseract OCR engine installed system-wide (this is a
separate binary, not a Python package):

- **Windows**: download the installer from
  https://github.com/UB-Mannheim/tesseract/wiki and install it. Make sure
  the install directory (e.g. `C:\Program Files\Tesseract-OCR`) is added
  to your PATH, or set it explicitly in `ocr_plate.py`:
  ```python
  pytesseract.pytesseract.tesseract_cmd = r"C:\Program Files\Tesseract-OCR\tesseract.exe"
  ```
- **Linux**: `sudo apt-get install tesseract-ocr`
- **macOS**: `brew install tesseract`

## Run

```bash
python app.py
```

Starts on `http://0.0.0.0:5001`.

## Endpoints

- `GET /health` - liveness check, returns how many vehicle records are loaded.
- `POST /verify-plate` - multipart form with an `image` file field.
  Returns JSON: `status` (MATCH/NO_MATCH), `confidence` (0-100),
  `vehicle` (matched record or null), `ocr_text`, `plate_pattern_matched`.

## Why Tesseract instead of EasyOCR

Originally scoped for EasyOCR, but EasyOCR pulls in a full GPU-oriented
PyTorch install (~3GB) purely to read short strings off a plate crop -
unnecessary weight for this service, and it blew the disk budget while
building this. Tesseract is a few MB, has no model-download step, and its
accuracy on cropped, upright, high-contrast plate text is comparable for
this use case. `extract_plate_text()` in `ocr_plate.py` is a self-contained
interface, so swapping back to EasyOCR later (if real plate accuracy needs
it) only means rewriting that one function.

## Pipeline

1. `detect_plate.py` - OpenCV contour-based plate region detection
   (Canny edges -> contours -> aspect-ratio + area filtering -> candidate
   crops, best-area-first).
2. `ocr_plate.py` - Tesseract OCR on each candidate (upscale, adaptive
   threshold + Otsu variants, alphanumeric whitelist), preferring a read
   that matches typical Indian plate shape.
3. `vehicle_database.py` - fuzzy match (thefuzz) against
   `vehicle_database.json`, tolerant of 1-2 character OCR errors but
   rejecting genuinely different plates (threshold=75/100).

## Tested

- Registered plate -> MATCH, 100% confidence, correct vehicle record
- Slightly OCR-garbled plate -> still MATCH (fuzzy tolerance working)
- Unregistered plate -> NO_MATCH
- Image with no plate-like region -> NO_MATCH, reason=no_plate_region_detected

`vehicle_database.json` currently has placeholder sample data - replace
with real resident/vehicle records before the demo.

## Next: wiring into the Spring Boot backend

Replace the stub in `VerificationService.java` (currently always returns
NO_MATCH) with an HTTP call to `POST http://localhost:5001/verify-plate`,
forwarding the captured image as multipart form data, and mapping the
JSON response's `status`/`vehicle` fields into whatever your backend's
verification result type looks like.
