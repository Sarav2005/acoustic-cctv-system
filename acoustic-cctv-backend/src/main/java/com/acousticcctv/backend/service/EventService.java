package com.acousticcctv.backend.service;

import com.acousticcctv.backend.model.Event;
import com.acousticcctv.backend.model.EventLabel;
import com.acousticcctv.backend.model.EventStatus;
import com.acousticcctv.backend.repository.EventRepository;
import com.acousticcctv.backend.service.TriggerDecisionService.TriggerDecision;
import com.acousticcctv.backend.service.VerificationService.VerificationResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final TriggerDecisionService triggerDecisionService;
    private final VerificationService verificationService;

    public EventService(EventRepository eventRepository,
                        TriggerDecisionService triggerDecisionService,
                        VerificationService verificationService) {
        this.eventRepository = eventRepository;
        this.triggerDecisionService = triggerDecisionService;
        this.verificationService = verificationService;
    }

    /** Called when the acoustic classifier posts a new detection. */
    public RegisteredEvent registerAudioEvent(String nodeId, EventLabel label, double confidence) {
        Event event = new Event(nodeId, label, confidence);

        TriggerDecision decision = triggerDecisionService.decide(label, confidence);
        event.setCameraTriggered(decision.shouldTrigger());
        event.setStatus(decision.shouldTrigger()
                ? (label == EventLabel.SCREAM ? EventStatus.ALERT : EventStatus.PENDING_CAPTURE)
                : EventStatus.UNVERIFIED);

        eventRepository.save(event);
        return new RegisteredEvent(event, decision.shouldTrigger(), decision.reason());
    }

    /**
     * Called when the camera module uploads a captured image for a given
     * event. Module 5 update: during continuous monitoring (see
     * live_infer.py), this now gets called repeatedly for the SAME
     * eventId - once per sampled frame - until the monitoring loop
     * resolves or times out. That's safe as-is: each call just overwrites
     * the event's capture/verification state with the latest frame's
     * result, so the event's final stored state naturally reflects
     * whichever frame ended the loop.
     */
    public Optional<Event> registerCapture(String eventId, String imagePath) {
        Optional<Event> maybeEvent = eventRepository.findById(eventId);
        if (maybeEvent.isEmpty()) {
            return Optional.empty();
        }

        Event event = maybeEvent.get();
        event.setImagePath(imagePath);
        event.setCapturedAt(Instant.now());
        event.setStatus(EventStatus.CAPTURED);

        VerificationResult result = verificationService.verify(event);
        event.setVerificationType(result.type());
        event.setStatus(result.status());
        event.setMatchedIdentity(result.matchedIdentity());
        event.setVerificationScore(result.score());
        event.setFaceCount(result.faceCount());
        event.setAnyUnknownFace(result.anyUnknownFace());
        event.setPersonDetected(result.personDetected());
        event.setVerificationDetails(result.rawDetails());

        eventRepository.save(event);
        return Optional.of(event);
    }

    /**
     * Called once by live_infer.py right after capture_and_monitor() ends
     * (MATCH, debounce-left, or timeout), with the finished video clip for
     * the whole monitoring window and the reason the loop stopped. This is
     * a single call per event (unlike registerCapture, which is called
     * once per sampled frame) - it doesn't touch verification state at
     * all, just attaches the clip + the "why it ended" the flat status
     * alone doesn't capture.
     */
    public Optional<Event> registerVideo(String eventId, String videoPath, String terminationReason) {
        Optional<Event> maybeEvent = eventRepository.findById(eventId);
        if (maybeEvent.isEmpty()) {
            return Optional.empty();
        }

        Event event = maybeEvent.get();
        event.setVideoPath(videoPath);
        event.setTerminationReason(terminationReason);
        event.setTerminatedAt(Instant.now());

        eventRepository.save(event);
        return Optional.of(event);
    }

    public List<Event> listAll() {
        return eventRepository.findAll();
    }

    public Optional<Event> findById(String id) {
        return eventRepository.findById(id);
    }

    public record RegisteredEvent(Event event, boolean triggerCamera, String reason) {}
}