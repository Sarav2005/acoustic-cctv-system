package com.acousticcctv.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/** Thin REST client for the backend's read-only event API - the gallery's
 * data source. */
public class EventApiClient {

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public EventApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Fetches all events, newest first. Throws on failure - caller should
     * run this off the JavaFX Application Thread and handle failures
     * (e.g. backend not running yet) gracefully. */
    public List<EventRecord> listEvents() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/events"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GET /api/events returned HTTP " + response.statusCode());
        }
        List<EventRecord> events = mapper.readValue(response.body(),
                mapper.getTypeFactory().constructCollectionType(List.class, EventRecord.class));
        events.sort(Comparator.comparing((EventRecord e) -> e.detectedAt).reversed());
        return events;
    }

    /** URL the JavaFX MediaPlayer should point at for this event's clip. */
    public String videoUrl(String eventId) {
        return baseUrl + "/api/events/" + eventId + "/video";
    }
}
