package com.acousticcctv.backend.dto;

/**
 * Ack returned by POST /api/captures/video - deliberately minimal (not the
 * full Event) so the video-upload contract stays decoupled from whatever
 * else Event grows over time, same reasoning as CaptureResponse for images.
 */
public class VideoUploadResponse {

    private String eventId;
    private String videoPath;
    private String terminationReason;

    public VideoUploadResponse(String eventId, String videoPath, String terminationReason) {
        this.eventId = eventId;
        this.videoPath = videoPath;
        this.terminationReason = terminationReason;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getVideoPath() { return videoPath; }
    public void setVideoPath(String videoPath) { this.videoPath = videoPath; }

    public String getTerminationReason() { return terminationReason; }
    public void setTerminationReason(String terminationReason) { this.terminationReason = terminationReason; }
}