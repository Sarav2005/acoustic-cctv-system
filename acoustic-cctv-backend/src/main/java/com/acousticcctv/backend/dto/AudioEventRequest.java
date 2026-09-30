package com.acousticcctv.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * What the acoustic classifier module sends (today: laptop-mic Python script;
 * later: ESP32 + I2S mic over Wi-Fi).
 *
 * Example JSON:
 * {
 *   "nodeId": "mic-node-1",
 *   "label": "CAR",
 *   "confidence": 0.91
 * }
 */
public class AudioEventRequest {

    @NotBlank
    private String nodeId;

    @NotBlank
    private String label; // matches EventLabel enum name, case-insensitive

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double confidence;

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
}
