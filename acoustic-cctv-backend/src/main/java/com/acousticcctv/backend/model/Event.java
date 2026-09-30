package com.acousticcctv.backend.model;

import com.acousticcctv.backend.dto.FaceBox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class Event {

    private final String id;
    private final String nodeId;
    private final EventLabel label;
    private final double confidence;
    private final Instant detectedAt;

    private boolean cameraTriggered;
    private EventStatus status;

    private String imagePath;
    private Instant capturedAt;

    private String verificationType;
    private String matchedIdentity;
    private Double verificationScore;

    private Integer faceCount;
    private Boolean anyUnknownFace;
    private Boolean personDetected;

    private String videoPath;
    private String terminationReason;
    private Instant terminatedAt;

    // Added for the dashboard's live camera-feed overlay - per-face
    // bbox+status+identity from the most recent verification sample.
    // Not persisted for historical value particularly, just threaded
    // through so CaptureController's response can hand it to
    // live_infer.py to draw with.
    private List<FaceBox> faces;

    private String verificationDetails;

    public Event(String nodeId, EventLabel label, double confidence) {
        this.id = UUID.randomUUID().toString();
        this.nodeId = nodeId;
        this.label = label;
        this.confidence = confidence;
        this.detectedAt = Instant.now();
        this.status = EventStatus.UNVERIFIED;
        this.cameraTriggered = false;
    }

    public String getId() { return id; }
    public String getNodeId() { return nodeId; }
    public EventLabel getLabel() { return label; }
    public double getConfidence() { return confidence; }
    public Instant getDetectedAt() { return detectedAt; }

    public boolean isCameraTriggered() { return cameraTriggered; }
    public void setCameraTriggered(boolean cameraTriggered) { this.cameraTriggered = cameraTriggered; }

    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus status) { this.status = status; }

    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }

    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }

    public String getVerificationType() { return verificationType; }
    public void setVerificationType(String verificationType) { this.verificationType = verificationType; }

    public String getMatchedIdentity() { return matchedIdentity; }
    public void setMatchedIdentity(String matchedIdentity) { this.matchedIdentity = matchedIdentity; }

    public Double getVerificationScore() { return verificationScore; }
    public void setVerificationScore(Double verificationScore) { this.verificationScore = verificationScore; }

    public Integer getFaceCount() { return faceCount; }
    public void setFaceCount(Integer faceCount) { this.faceCount = faceCount; }

    public Boolean getAnyUnknownFace() { return anyUnknownFace; }
    public void setAnyUnknownFace(Boolean anyUnknownFace) { this.anyUnknownFace = anyUnknownFace; }

    public Boolean getPersonDetected() { return personDetected; }
    public void setPersonDetected(Boolean personDetected) { this.personDetected = personDetected; }

    public String getVideoPath() { return videoPath; }
    public void setVideoPath(String videoPath) { this.videoPath = videoPath; }

    public String getTerminationReason() { return terminationReason; }
    public void setTerminationReason(String terminationReason) { this.terminationReason = terminationReason; }

    public Instant getTerminatedAt() { return terminatedAt; }
    public void setTerminatedAt(Instant terminatedAt) { this.terminatedAt = terminatedAt; }

    public List<FaceBox> getFaces() { return faces; }
    public void setFaces(List<FaceBox> faces) { this.faces = faces; }

    public String getVerificationDetails() { return verificationDetails; }
    public void setVerificationDetails(String verificationDetails) { this.verificationDetails = verificationDetails; }
}