package com.acousticcctv.backend.service;

import com.acousticcctv.backend.dto.FaceBox;
import com.acousticcctv.backend.model.Event;
import com.acousticcctv.backend.model.EventLabel;
import com.acousticcctv.backend.model.EventStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Module 4 (ANPR) and Module 5 (face recognition) are both wired in.
 * Called repeatedly per event during continuous monitoring (once per
 * sampled frame).
 *
 * Safety check update: verifyPlate() now ALSO runs the same frame
 * through face-service, purely to check who's driving. A matched plate
 * doesn't guarantee the recognized owner is actually the one driving -
 * this reuses the same faceCount/anyUnknownFace/personDetected/faces
 * fields FACE events already carry, so a PLATE event with
 * anyUnknownFace == true alongside a MATCH means "this is a known
 * vehicle, but I don't recognize who's driving it" - the dashboard
 * flags that combination as a distinct danger case.
 */
@Service
public class VerificationService {

    private static final String ANPR_VERIFY_URL = "http://localhost:5001/verify-plate";
    private static final String FACE_VERIFY_URL = "http://localhost:5002/verify-faces";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VerificationResult verify(Event event) {
        String type = (event.getLabel() == EventLabel.CAR || event.getLabel() == EventLabel.BIKE)
                ? "PLATE" : "FACE";

        if (event.getImagePath() == null) {
            return new VerificationResult(type, EventStatus.NO_MATCH, null, null, null, null, null, null, null);
        }

        return "PLATE".equals(type) ? verifyPlate(event) : verifyFace(event);
    }

    private VerificationResult verifyPlate(Event event) {
        try {
            String rawJson = postImage(ANPR_VERIFY_URL, event.getImagePath());
            AnprResponse response = objectMapper.readValue(rawJson, AnprResponse.class);

            EventStatus status = "MATCH".equalsIgnoreCase(response.status())
                    ? EventStatus.MATCH
                    : EventStatus.NO_MATCH;

            String matchedIdentity = (response.vehicle() != null) ? response.vehicle().plate() : null;
            Double score = (response.confidence() != null) ? response.confidence().doubleValue() : null;

            FaceCheckResult driverCheck = checkDriverFace(event.getImagePath());

            return new VerificationResult("PLATE", status, matchedIdentity, score,
                    driverCheck.faceCount(), driverCheck.anyUnknownFace(), driverCheck.personDetected(),
                    driverCheck.faces(), rawJson);

        } catch (RestClientException | JsonProcessingException ex) {
            return new VerificationResult("PLATE", EventStatus.NO_MATCH, null, null, null, null, null, null, null);
        }
    }

    /** Runs the same captured frame through face-service purely to check
     * who's driving - used only by verifyPlate(). Failures here (e.g.
     * face-service unreachable) shouldn't fail the plate verification
     * itself, so this swallows its own errors. */
    private FaceCheckResult checkDriverFace(String imagePath) {
        try {
            String rawJson = postImage(FACE_VERIFY_URL, imagePath);
            FaceServiceResponse response = objectMapper.readValue(rawJson, FaceServiceResponse.class);
            List<FaceResult> faces = response.faces() == null ? List.of() : response.faces();
            List<FaceBox> faceBoxes = faces.stream()
                    .map(f -> new FaceBox(
                            f.bbox().get(0), f.bbox().get(1), f.bbox().get(2), f.bbox().get(3),
                            f.status(), f.identity()))
                    .collect(Collectors.toList());
            return new FaceCheckResult(response.face_count(), response.any_unknown(),
                    response.person_detected(), faceBoxes);
        } catch (Exception ex) {
            return new FaceCheckResult(null, null, null, null);
        }
    }

    private VerificationResult verifyFace(Event event) {
        try {
            String rawJson = postImage(FACE_VERIFY_URL, event.getImagePath());
            FaceServiceResponse response = objectMapper.readValue(rawJson, FaceServiceResponse.class);

            boolean resolved = response.face_count() > 0 && response.all_matched();
            EventStatus status = resolved ? EventStatus.MATCH : EventStatus.NO_MATCH;

            List<FaceResult> faces = response.faces() == null ? List.of() : response.faces();
            List<FaceResult> matchedFaces = faces.stream()
                    .filter(f -> "MATCH".equalsIgnoreCase(f.status()))
                    .collect(Collectors.toList());

            String matchedIdentity = matchedFaces.isEmpty() ? null :
                    matchedFaces.stream().map(FaceResult::identity).collect(Collectors.joining(", "));

            Double score = null;
            if (!matchedFaces.isEmpty()) {
                score = matchedFaces.stream().mapToDouble(FaceResult::confidence).max().orElse(0.0);
            } else if (!faces.isEmpty()) {
                score = faces.stream().mapToDouble(FaceResult::confidence).max().orElse(0.0);
            }

            List<FaceBox> faceBoxes = faces.stream()
                    .map(f -> new FaceBox(
                            f.bbox().get(0), f.bbox().get(1), f.bbox().get(2), f.bbox().get(3),
                            f.status(), f.identity()))
                    .collect(Collectors.toList());

            return new VerificationResult(
                    "FACE", status, matchedIdentity, score,
                    response.face_count(), response.any_unknown(), response.person_detected(),
                    faceBoxes, rawJson);

        } catch (RestClientException | JsonProcessingException ex) {
            return new VerificationResult("FACE", EventStatus.NO_MATCH, null, null, null, null, null, null, null);
        }
    }

    private String postImage(String url, String imagePath) {
        File imageFile = new File(imagePath);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new FileSystemResource(imageFile));

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        return restTemplate.postForObject(url, requestEntity, String.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnprResponse(String status, Integer confidence, VehicleRecord vehicle) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VehicleRecord(String plate, String owner_name, String flat_number, String vehicle_type, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FaceServiceResponse(int face_count, List<FaceResult> faces, boolean any_unknown, boolean all_matched,
                               boolean person_detected) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FaceResult(List<Integer> bbox, double det_score, String status, double confidence,
                      String identity, String flat_number) {}

    private record FaceCheckResult(Integer faceCount, Boolean anyUnknownFace, Boolean personDetected,
                                   List<FaceBox> faces) {}

    public record VerificationResult(String type, EventStatus status, String matchedIdentity, Double score,
                                     Integer faceCount, Boolean anyUnknownFace, Boolean personDetected,
                                     List<FaceBox> faces, String rawDetails) {}
}