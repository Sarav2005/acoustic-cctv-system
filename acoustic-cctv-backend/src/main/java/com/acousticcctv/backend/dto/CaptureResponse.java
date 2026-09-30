package com.acousticcctv.backend.dto;

import java.util.List;

public class CaptureResponse {

    private String eventId;
    private String status;
    private String verificationType;
    private String matchedIdentity;
    private Double verificationScore;
    private Integer faceCount;
    private Boolean anyUnknownFace;
    private Boolean personDetected;
    private List<FaceBox> faces;

    public CaptureResponse(String eventId, String status, String verificationType,
                           String matchedIdentity, Double verificationScore,
                           Integer faceCount, Boolean anyUnknownFace,
                           Boolean personDetected, List<FaceBox> faces) {
        this.eventId = eventId;
        this.status = status;
        this.verificationType = verificationType;
        this.matchedIdentity = matchedIdentity;
        this.verificationScore = verificationScore;
        this.faceCount = faceCount;
        this.anyUnknownFace = anyUnknownFace;
        this.personDetected = personDetected;
        this.faces = faces;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

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

    public List<FaceBox> getFaces() { return faces; }
    public void setFaces(List<FaceBox> faces) { this.faces = faces; }
}