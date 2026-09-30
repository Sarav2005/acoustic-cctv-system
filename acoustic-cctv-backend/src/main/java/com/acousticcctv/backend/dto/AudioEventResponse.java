package com.acousticcctv.backend.dto;

/**
 * What the backend replies with after an audio event is posted. The camera
 * module (laptop webcam script today, ESP32-CAM later) uses eventId when it
 * later uploads the captured image.
 */
public class AudioEventResponse {

    private String eventId;
    private boolean triggerCamera;
    private String reason;

    public AudioEventResponse(String eventId, boolean triggerCamera, String reason) {
        this.eventId = eventId;
        this.triggerCamera = triggerCamera;
        this.reason = reason;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public boolean isTriggerCamera() { return triggerCamera; }
    public void setTriggerCamera(boolean triggerCamera) { this.triggerCamera = triggerCamera; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
