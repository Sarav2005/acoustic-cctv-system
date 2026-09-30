package com.acousticcctv.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the acoustic-triggered CCTV backend.
 *
 * This service is the "brain" of the project:
 *   1. Receives sound-classification events from the acoustic node
 *      (today: a laptop-mic Python script; later: ESP32 + I2S mic).
 *   2. Decides whether the event should wake the camera.
 *   3. Receives the captured image from the camera module
 *      (today: laptop webcam script; later: ESP32-CAM / Pi).
 *   4. Forwards the image to the verification stage (ANPR / face recognition)
 *      and records the match result.
 *   5. Exposes everything for an admin dashboard to query.
 */
@SpringBootApplication
public class AcousticCctvBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(AcousticCctvBackendApplication.class, args);
    }
}
