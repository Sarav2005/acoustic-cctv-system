package com.acousticcctv.backend.service;

import com.acousticcctv.backend.model.EventLabel;
import org.springframework.stereotype.Service;

/**
 * Encapsulates the "is this worth waking the camera for?" business rule.
 * Kept separate from the controller so the policy can evolve (e.g. per-label
 * confidence thresholds, cooldown windows so a running car doesn't spam
 * triggers every second) without touching the HTTP layer.
 */
@Service
public class TriggerDecisionService {

    // Minimum confidence before we trust a classification enough to act on it.
    private static final double CONFIDENCE_THRESHOLD = 0.65;

    public TriggerDecision decide(EventLabel label, double confidence) {
        if (label == EventLabel.SCREAM && confidence >= 0.5) {
            // Screams get a lower bar and are always treated as urgent.
            return new TriggerDecision(true, "scream detected — treated as alert regardless of confidence");
        }

        if (confidence < CONFIDENCE_THRESHOLD) {
            return new TriggerDecision(false, "confidence below threshold (" + confidence + " < " + CONFIDENCE_THRESHOLD + ")");
        }

        return switch (label) {
            case CAR, BIKE -> new TriggerDecision(true, "vehicle sound detected — waking camera for ANPR");
            case FOOTSTEPS -> new TriggerDecision(true, "footsteps detected — waking camera for face verification");
            case SCREAM -> new TriggerDecision(true, "scream detected — treated as alert");
            case UNKNOWN -> new TriggerDecision(false, "unrecognized sound class — ignoring");
        };
    }

    public record TriggerDecision(boolean shouldTrigger, String reason) {}
}
