package com.acousticcctv.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EventRecord {
    public String id;
    public String nodeId;
    public String label;
    public double confidence;
    public Instant detectedAt;
    public boolean cameraTriggered;
    public String status;
    public String imagePath;
    public String verificationType;
    public String matchedIdentity;
    public Double verificationScore;
    public Integer faceCount;
    public Boolean anyUnknownFace;
    public Boolean personDetected;
    public String videoPath;
    public String terminationReason;
    public Instant terminatedAt;
}