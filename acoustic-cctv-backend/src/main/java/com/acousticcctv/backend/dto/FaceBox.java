package com.acousticcctv.backend.dto;

/**
 * Minimal per-face info for the live camera-feed overlay - just enough
 * for the client to draw a box and a MATCH/NO_MATCH label over the
 * right face, not the full detection record (embedding, det_score,
 * flat_number) that verificationDetails already carries for anyone who
 * needs that. Bbox uses the same (x, y, w, h) convention as
 * face_recognizer.py's detect_faces().
 */
public class FaceBox {
    public int x;
    public int y;
    public int w;
    public int h;
    public String status;    // "MATCH" or "NO_MATCH"
    public String identity;  // null if NO_MATCH

    public FaceBox(int x, int y, int w, int h, String status, String identity) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.status = status;
        this.identity = identity;
    }
}