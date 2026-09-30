package com.acousticcctv.backend.model;

/**
 * Lifecycle of a single detection event, end to end.
 *
 *   PENDING_CAPTURE   -> audio event received, camera has been told to wake, waiting for image
 *   CAPTURED          -> image received, waiting for verification (ANPR / face)
 *   MATCH             -> verification found the plate/face in the resident/vehicle database
 *   NO_MATCH          -> verification ran but found no match (possible intruder/unknown vehicle)
 *   UNVERIFIED        -> event did not require camera capture (e.g. low-confidence detection)
 *   ALERT             -> scream detected — treated as an immediate alert, capture + notify regardless of match
 */
public enum EventStatus {
    PENDING_CAPTURE,
    CAPTURED,
    MATCH,
    NO_MATCH,
    UNVERIFIED,
    ALERT
}
