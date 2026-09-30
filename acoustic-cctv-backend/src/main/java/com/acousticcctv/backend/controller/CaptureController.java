package com.acousticcctv.backend.controller;

import com.acousticcctv.backend.dto.CaptureResponse;
import com.acousticcctv.backend.dto.VideoUploadResponse;
import com.acousticcctv.backend.model.Event;
import com.acousticcctv.backend.service.EventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

@RestController
@RequestMapping("/api/captures")
public class CaptureController {

    private static final String STORAGE_DIR = "captured-images";
    private static final String VIDEO_STORAGE_DIR = "captured-videos";

    private final EventService eventService;

    public CaptureController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<CaptureResponse> uploadCapture(
            @RequestParam("eventId") String eventId,
            @RequestParam("image") MultipartFile image) throws IOException {

        Files.createDirectories(Paths.get(STORAGE_DIR));
        String filename = eventId + "_" + System.currentTimeMillis() + ".jpg";
        Path destination = Paths.get(STORAGE_DIR, filename);
        image.transferTo(destination);

        Optional<Event> updated = eventService.registerCapture(eventId, destination.toString());

        if (updated.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Event event = updated.get();
        CaptureResponse response = new CaptureResponse(
                event.getId(),
                event.getStatus().name(),
                event.getVerificationType(),
                event.getMatchedIdentity(),
                event.getVerificationScore(),
                event.getFaceCount(),
                event.getAnyUnknownFace(),
                event.getPersonDetected(),
                event.getFaces());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/video")
    public ResponseEntity<VideoUploadResponse> uploadVideo(
            @RequestParam("eventId") String eventId,
            @RequestParam("video") MultipartFile video,
            @RequestParam("terminationReason") String terminationReason) throws IOException {

        Files.createDirectories(Paths.get(VIDEO_STORAGE_DIR));
        String filename = eventId + ".mp4";
        Path destination = Paths.get(VIDEO_STORAGE_DIR, filename);
        video.transferTo(destination);

        Optional<Event> updated = eventService.registerVideo(eventId, destination.toString(), terminationReason);

        if (updated.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Event event = updated.get();
        VideoUploadResponse response = new VideoUploadResponse(
                event.getId(), event.getVideoPath(), event.getTerminationReason());

        return ResponseEntity.ok(response);
    }
}