package com.acousticcctv.dashboard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Talks directly to face-service's /enroll endpoint (bypasses the Spring
 * backend, which doesn't proxy enrollment - this is an admin action, not
 * part of the detection pipeline). Hand-builds the multipart/form-data
 * body since java.net.http.HttpClient has no built-in multipart support.
 */
public class FaceEnrollmentClient {

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public FaceEnrollmentClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Returns face-service's raw JSON response body on success. Throws
     * on any failure - caller (a background Task) catches and surfaces
     * the message. */
    public String enroll(String name, String flatNumber, String status, Path imagePath)
            throws IOException, InterruptedException {
        String boundary = "----AcousticCCTVBoundary" + UUID.randomUUID();
        byte[] imageBytes = Files.readAllBytes(imagePath);
        String filename = imagePath.getFileName().toString();

        StringBuilder body = new StringBuilder();
        appendField(body, boundary, "name", name);
        if (flatNumber != null && !flatNumber.isBlank()) {
            appendField(body, boundary, "flat_number", flatNumber);
        }
        if (status != null && !status.isBlank()) {
            appendField(body, boundary, "status", status);
        }
        body.append("--").append(boundary).append("\r\n");
        body.append("Content-Disposition: form-data; name=\"image\"; filename=\"").append(filename).append("\"\r\n");
        body.append("Content-Type: image/jpeg\r\n\r\n");

        byte[] head = body.toString().getBytes();
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes();

        byte[] fullBody = new byte[head.length + imageBytes.length + tail.length];
        System.arraycopy(head, 0, fullBody, 0, head.length);
        System.arraycopy(imageBytes, 0, fullBody, head.length, imageBytes.length);
        System.arraycopy(tail, 0, fullBody, head.length + imageBytes.length, tail.length);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/enroll"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(fullBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("face-service returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static void appendField(StringBuilder body, String boundary, String name, String value) {
        body.append("--").append(boundary).append("\r\n");
        body.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n");
        body.append(value).append("\r\n");
    }
}