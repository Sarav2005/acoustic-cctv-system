package com.acousticcctv.backend.controller;

import com.acousticcctv.backend.model.Event;
import com.acousticcctv.backend.service.EventService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Read-only API for the JavaFX dashboard: the gallery list/detail views,
 * plus (new) video playback for whichever event has a recorded clip.
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public List<Event> listEvents() {
        return eventService.listAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Event> getEvent(@PathVariable String id) {
        return eventService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Serves the event's video clip with HTTP range support, so a player
     * (JavaFX's MediaPlayer, a browser <video> tag, etc.) can seek within
     * it instead of only ever playing from the start. Standard Spring
     * ResourceRegion pattern for seekable media - each request without a
     * Range header gets the first chunk (not the whole file at once);
     * requests with a Range header get exactly the requested byte range.
     */
    @GetMapping("/{id}/video")
    public ResponseEntity<ResourceRegion> streamVideo(
            @PathVariable String id,
            @RequestHeader HttpHeaders headers) throws IOException {

        Optional<Event> maybeEvent = eventService.findById(id);
        if (maybeEvent.isEmpty() || maybeEvent.get().getVideoPath() == null) {
            return ResponseEntity.notFound().build();
        }

        Path path = Paths.get(maybeEvent.get().getVideoPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource video = new FileSystemResource(path);
        long contentLength = video.contentLength();
        List<HttpRange> ranges = headers.getRange();

        ResourceRegion region;
        if (ranges.isEmpty()) {
            long rangeLength = Math.min(1024 * 1024, contentLength);
            region = new ResourceRegion(video, 0, rangeLength);
        } else {
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(contentLength);
            long end = range.getRangeEnd(contentLength);
            long rangeLength = Math.min(1024 * 1024, end - start + 1);
            region = new ResourceRegion(video, start, rangeLength);
        }

        return ResponseEntity.status(ranges.isEmpty() ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.valueOf("video/mp4"))
                .body(region);
    }
}