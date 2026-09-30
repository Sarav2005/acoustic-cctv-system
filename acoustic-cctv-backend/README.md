# Acoustic-CCTV Backend (Module 2)

This is the Spring Boot skeleton that every other module plugs into. It works
identically whether module 1/3 are laptop scripts (now) or real ESP32
hardware (later) — they just hit the same HTTP endpoints.

## Run it

```bash
cd acoustic-cctv-backend
mvn spring-boot:run
```

Server starts on `http://localhost:8080`.

## API contract

### 1. Acoustic classifier -> backend

`POST /api/audio/events`

```json
{
  "nodeId": "mic-node-1",
  "label": "CAR",
  "confidence": 0.91
}
```

Valid `label` values: `CAR`, `BIKE`, `FOOTSTEPS`, `SCREAM` (anything else maps
to `UNKNOWN`).

Response:

```json
{
  "eventId": "3f9c2b8e-...",
  "triggerCamera": true,
  "reason": "vehicle sound detected — waking camera for ANPR"
}
```

Trigger logic lives in `TriggerDecisionService` — vehicles/footsteps trigger
above a 0.65 confidence threshold, screams trigger at 0.5 and are always
flagged as an alert.

### 2. Camera module -> backend

`POST /api/captures` (multipart/form-data)

- `eventId`: the id returned from step 1
- `image`: the captured JPEG

Response:

```json
{
  "eventId": "3f9c2b8e-...",
  "status": "NO_MATCH",
  "verificationType": "PLATE",
  "matchedIdentity": null,
  "verificationScore": null
}
```

Right now `VerificationService` is a **stub** that always returns `NO_MATCH`
— that's intentional. It's the plug point for module 4 (ANPR) and module 5
(face recognition PoC): once those exist, replace the body of
`VerificationService.verify(...)` with a real HTTP call to the Python
microservice, and nothing in the controllers needs to change.

### 3. Dashboard reads

`GET /api/events` — list all events, newest first
`GET /api/events/{id}` — a single event

## Testing it manually right now (before modules 1/3 exist)

```bash
# Simulate an acoustic detection
curl -X POST http://localhost:8080/api/audio/events \
  -H "Content-Type: application/json" \
  -d '{"nodeId":"mic-node-1","label":"CAR","confidence":0.9}'

# Copy the eventId from the response, then simulate a camera capture
# (use any jpg you have lying around)
curl -X POST http://localhost:8080/api/captures \
  -F "eventId=<paste-event-id-here>" \
  -F "image=@/path/to/some.jpg"

# Check the dashboard feed
curl http://localhost:8080/api/events
```

## What's deliberately stubbed / in-memory right now

- **EventRepository** is an in-memory `ConcurrentHashMap`, not a database.
  Fine for development; swap for Spring Data JPA + H2/Postgres once the
  pipeline is validated end-to-end — no controller/service code changes
  needed since they only depend on the repository's method signatures.
- **VerificationService** always returns `NO_MATCH` — it's the seam where
  module 4 (ANPR) and module 5 (face recognition) plug in.

## Next module

Module 1 — acoustic classifier — using your laptop's mic to post real
`CAR` / `BIKE` / `FOOTSTEPS` / `SCREAM` events to `/api/audio/events` above.
