package com.acousticcctv.dashboard;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Shared formatting/rendering helpers for EventRecord, used by both the
 * ADMIN dashboard's Live Monitoring/Recordings sections and the RESIDENT
 * view's My Activity/My Recordings sections, so the two stay visually and
 * behaviorally consistent instead of drifting apart.
 */
public final class EventDisplay {

    private EventDisplay() { }

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM, hh:mm:ss a").withZone(ZoneId.systemDefault());

    public static String formatTime(Instant instant) {
        return instant != null ? TIME_FORMAT.format(instant) : "-";
    }

    /**
     * An event counts as "verification completed" once it has resolved to
     * MATCH or NO_MATCH. PENDING_CAPTURE/CAPTURED (still in flight),
     * UNVERIFIED (never triggered the camera) and ALERT (a scream's
     * transient pre-capture state) are all still "unprocessed" and are
     * filtered out of both the admin and resident event/recordings views.
     */
    public static boolean isVerified(EventRecord e) {
        return "MATCH".equals(e.status) || "NO_MATCH".equals(e.status);
    }

    public static String friendlyLabel(String label) {
        if (label == null) return "Unknown sound";
        return switch (label) {
            case "FOOTSTEPS" -> "Footsteps";
            case "CAR" -> "Vehicle (Car)";
            case "BIKE" -> "Vehicle (Bike)";
            case "SCREAM" -> "Scream";
            default -> label;
        };
    }

    public static String[] friendlyStatus(EventRecord e) {
        boolean isScream = "SCREAM".equals(e.label);
        boolean unknownDriver = "PLATE".equals(e.verificationType)
                && "MATCH".equals(e.status)
                && Boolean.TRUE.equals(e.anyUnknownFace);

        if (unknownDriver) {
            return new String[]{"Vehicle matched - unknown driver", "badge-danger"};
        }
        if (isScream) {
            if (e.terminationReason == null) {
                return new String[]{"SCREAM - recording", "badge-danger"};
            }
            return new String[]{"SCREAM alert - recorded", "badge-danger"};
        }
        if ("UNVERIFIED".equals(e.status)) {
            return new String[]{"Too quiet to act on", "badge-unverified"};
        }
        if ("PENDING_CAPTURE".equals(e.status) || "CAPTURED".equals(e.status)) {
            return new String[]{"Verifying...", "badge-unverified"};
        }
        if ("MATCH".equals(e.status)) {
            return new String[]{"Matched", "badge-matched"};
        }
        if ("NO_MATCH".equals(e.status)) {
            if ("LEFT_FRAME".equals(e.terminationReason)) {
                return new String[]{"Left before identified", "badge-notmatched"};
            }
            if ("TIMEOUT".equals(e.terminationReason)) {
                return new String[]{"Not identified", "badge-notmatched"};
            }
            return new String[]{"Verifying...", "badge-unverified"};
        }
        if ("ALERT".equals(e.status)) {
            return new String[]{"Alert", "badge-danger"};
        }
        return new String[]{e.status != null ? e.status : "Unknown", "badge-unverified"};
    }

    public static void loadThumbnail(ImageView view, EventRecord event, String backendDir) {
        if (event == null || event.imagePath == null) {
            view.setImage(null);
            return;
        }
        File imageFile = new File(backendDir, event.imagePath);
        if (!imageFile.exists()) {
            view.setImage(null);
            return;
        }
        try {
            view.setImage(new Image(imageFile.toURI().toString()));
        } catch (Exception ex) {
            view.setImage(null);
        }
    }

    /** Shared event-list row rendering for both the admin Live Monitoring
     * list and the resident My Activity list. */
    public static class EventCell extends ListCell<EventRecord> {
        private final Label labelText = new Label();
        private final Label timeText = new Label();
        private final Label badge = new Label();
        private final VBox textBox = new VBox(2, labelText, timeText);
        private final HBox row = new HBox(10, textBox, badge);

        public EventCell() {
            labelText.getStyleClass().add("event-cell-label");
            timeText.getStyleClass().add("event-cell-time");
            badge.getStyleClass().add("badge");
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("event-cell");
            HBox.setHgrow(textBox, Priority.ALWAYS);
        }

        @Override
        protected void updateItem(EventRecord event, boolean empty) {
            super.updateItem(event, empty);
            if (empty || event == null) {
                setGraphic(null);
                return;
            }
            labelText.setText(friendlyLabel(event.label));
            timeText.setText(formatTime(event.detectedAt));

            String[] status = friendlyStatus(event);
            badge.setText(status[0]);
            badge.getStyleClass().removeIf(c -> c.startsWith("badge-"));
            badge.getStyleClass().add(status[1]);

            row.getStyleClass().remove("alert-row");
            if ("SCREAM".equals(event.label) || "badge-danger".equals(status[1])) {
                row.getStyleClass().add("alert-row");
            }

            setGraphic(row);
        }
    }
}