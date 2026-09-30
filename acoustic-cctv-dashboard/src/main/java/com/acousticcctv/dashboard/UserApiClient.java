package com.acousticcctv.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UserApiClient {

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public UserApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public record LoggedInUser(String id, String username, String role, String displayName, String flatNumber,
                               String profilePhotoPath) {}

    public LoggedInUser login(String username, String password) throws IOException, InterruptedException {
        String jsonBody = mapper.writeValueAsString(Map.of("username", username, "password", password));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/users/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode node = mapper.readTree(response.body());
        if (response.statusCode() != 200) {
            throw new IOException(node.has("message") ? node.get("message").asText() : "Login failed.");
        }
        return new LoggedInUser(
                node.get("id").asText(), node.get("username").asText(), node.get("role").asText(),
                node.get("displayName").asText(), node.path("flatNumber").asText(""),
                node.path("profilePhotoPath").asText(""));
    }

    public void requestAccount(String username, String password, String displayName, String flatNumber,
                               Path photo) throws IOException, InterruptedException {
        String boundary = "----AcousticCCTVBoundary" + UUID.randomUUID();
        byte[] imageBytes = Files.readAllBytes(photo);
        String filename = photo.getFileName().toString();

        StringBuilder body = new StringBuilder();
        appendField(body, boundary, "username", username);
        appendField(body, boundary, "password", password);
        appendField(body, boundary, "displayName", displayName);
        if (flatNumber != null && !flatNumber.isBlank()) {
            appendField(body, boundary, "flatNumber", flatNumber);
        }
        body.append("--").append(boundary).append("\r\n");
        body.append("Content-Disposition: form-data; name=\"photo\"; filename=\"").append(filename).append("\"\r\n");
        body.append("Content-Type: image/jpeg\r\n\r\n");

        byte[] head = body.toString().getBytes();
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes();
        byte[] fullBody = new byte[head.length + imageBytes.length + tail.length];
        System.arraycopy(head, 0, fullBody, 0, head.length);
        System.arraycopy(imageBytes, 0, fullBody, head.length, imageBytes.length);
        System.arraycopy(tail, 0, fullBody, head.length + imageBytes.length, tail.length);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/users/request-account"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(fullBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            JsonNode node = mapper.readTree(response.body());
            throw new IOException(node.has("message") ? node.get("message").asText() : "Request failed.");
        }
    }

    public record PendingRequest(String id, String username, String displayName, String flatNumber, String requestedAt) {}

    public List<PendingRequest> pendingRequests() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/users/pending"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode array = mapper.readTree(response.body());
        List<PendingRequest> results = new ArrayList<>();
        for (JsonNode node : array) {
            results.add(new PendingRequest(
                    node.get("id").asText(), node.get("username").asText(), node.get("displayName").asText(),
                    node.path("flatNumber").asText(""), node.get("requestedAt").asText()));
        }
        return results;
    }

    public void approve(String id) throws IOException, InterruptedException {
        post(baseUrl + "/api/users/" + id + "/approve");
    }

    public void reject(String id) throws IOException, InterruptedException {
        post(baseUrl + "/api/users/" + id + "/reject");
    }

    private void post(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Request failed: HTTP " + response.statusCode());
        }
    }

    public record ProfileUpdateResult(String displayName, String profilePhotoPath) {}

    public ProfileUpdateResult updateProfile(String userId, String newDisplayName, Path newPhoto)
            throws IOException, InterruptedException {
        String boundary = "----AcousticCCTVBoundary" + UUID.randomUUID();
        StringBuilder body = new StringBuilder();
        if (newDisplayName != null && !newDisplayName.isBlank()) {
            appendField(body, boundary, "displayName", newDisplayName);
        }
        byte[] imageBytes = new byte[0];
        if (newPhoto != null) {
            imageBytes = Files.readAllBytes(newPhoto);
            body.append("--").append(boundary).append("\r\n");
            body.append("Content-Disposition: form-data; name=\"photo\"; filename=\"")
                    .append(newPhoto.getFileName().toString()).append("\"\r\n");
            body.append("Content-Type: image/jpeg\r\n\r\n");
        }
        byte[] head = body.toString().getBytes();
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes();
        byte[] fullBody = new byte[head.length + imageBytes.length + tail.length];
        System.arraycopy(head, 0, fullBody, 0, head.length);
        System.arraycopy(imageBytes, 0, fullBody, head.length, imageBytes.length);
        System.arraycopy(tail, 0, fullBody, head.length + imageBytes.length, tail.length);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/users/" + userId + "/profile"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method("PUT", HttpRequest.BodyPublishers.ofByteArray(fullBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode node = mapper.readTree(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new IOException(node.has("message") ? node.get("message").asText() : "Update failed.");
        }
        return new ProfileUpdateResult(node.path("displayName").asText(null), node.path("profilePhotoPath").asText(null));
    }

    public void changePassword(String userId, String currentPassword, String newPassword)
            throws IOException, InterruptedException {
        String jsonBody = mapper.writeValueAsString(
                Map.of("currentPassword", currentPassword, "newPassword", newPassword));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/users/" + userId + "/password"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            JsonNode node = mapper.readTree(response.body());
            throw new IOException(node.has("message") ? node.get("message").asText() : "Password change failed.");
        }
    }

    private static void appendField(StringBuilder body, String boundary, String name, String value) {
        body.append("--").append(boundary).append("\r\n");
        body.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n");
        body.append(value).append("\r\n");
    }
}