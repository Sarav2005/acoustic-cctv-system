package com.acousticcctv.backend.controller;

import com.acousticcctv.backend.dto.AudioEventRequest;
import com.acousticcctv.backend.dto.AudioEventResponse;
import com.acousticcctv.backend.model.EventLabel;
import com.acousticcctv.backend.service.EventService;
import com.acousticcctv.backend.service.EventService.RegisteredEvent;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Consumed by module 1 (acoustic classifier) — today, a laptop-mic Python
 * script; later, the ESP32 + I2S mic node over Wi-Fi. Same endpoint either
 * way, which is the whole point of the laptop-first approach.
 */
@RestController
@RequestMapping("/api/audio")
public class AudioEventController {

    private final EventService eventService;

    public AudioEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping("/events")
    public ResponseEntity<AudioEventResponse> postAudioEvent(@Valid @RequestBody AudioEventRequest request) {
        EventLabel label;
        try {
            label = EventLabel.valueOf(request.getLabel().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            label = EventLabel.UNKNOWN;
        }

        RegisteredEvent registered = eventService.registerAudioEvent(
                request.getNodeId(), label, request.getConfidence());

        AudioEventResponse response = new AudioEventResponse(
                registered.event().getId(),
                registered.triggerCamera(),
                registered.reason());

        return ResponseEntity.ok(response);
    }
}
