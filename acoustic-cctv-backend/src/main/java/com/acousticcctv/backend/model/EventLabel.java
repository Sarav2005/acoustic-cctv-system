package com.acousticcctv.backend.model;

/**
 * Sound classes the acoustic classifier (module 1) can output.
 * Keep this in sync with whatever labels the TinyML/Python classifier uses.
 */
public enum EventLabel {
    CAR,
    BIKE,
    FOOTSTEPS,
    SCREAM,
    UNKNOWN
}
