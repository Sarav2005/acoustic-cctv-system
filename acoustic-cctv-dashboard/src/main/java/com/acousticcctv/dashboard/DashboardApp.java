package com.acousticcctv.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class DashboardApp extends Application {

    private ServiceConfig config;
    private ProcessOrchestrator orchestrator;
    private EventApiClient apiClient;
    private UserApiClient userApiClient;
    private Stage stage;
    private final ObjectMapper audioMapper = new ObjectMapper();

    private boolean backendStarting = false;
    private boolean backendReady = false;

    private UserApiClient.LoggedInUser currentUser;

    private ToggleButton powerToggle;
    private TextArea logArea;
    private boolean livePollingStarted = false;
    private StackPane sectionContainer;
    private final List<Button> sidebarButtons = new ArrayList<>();

    // Persists for the whole admin session (survives switching sidebar
    // tabs, unlike a plain ListView whose items get rebuilt from scratch
    // every time buildMonitorSection() runs) - both the event list and the
    // recordings gallery are populated FROM this, so navigating away and
    // back never shows an empty list while a background refresh catches up.
    private final ObservableList<EventRecord> masterEvents = FXCollections.observableArrayList();
    private ListView<EventRecord> eventList;
    private EventRecord selectedEvent;

    private Label detailTitle;
    private Label detailBadge;
    private Label detailLine1;
    private Label detailLine2;
    private ImageView thumbnailView;
    private Button playButton;

    private ImageView liveFeedView;
    private Label liveFeedPlaceholder;
    private long lastLiveFrameModified = -1;

    private Canvas audioCanvas;
    private long lastLiveAudioModified = -1;

    private static final int POINTS_PER_WINDOW_IN_HISTORY = 10;
    private static final int HISTORY_WINDOWS = 10;
    private static final int HISTORY_MAX_POINTS = POINTS_PER_WINDOW_IN_HISTORY * HISTORY_WINDOWS;
    private final Deque<Double> audioHistory = new ArrayDeque<>();
    private final Deque<Boolean> audioTriggerHistory = new ArrayDeque<>();
    private String lastAudioLabel;
    private Double lastAudioConfidence;

    private FlowPane recordingsGallery;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        config = new ServiceConfig();
        orchestrator = new ProcessOrchestrator(config);
        apiClient = new EventApiClient(config.get("backend.base.url"));
        userApiClient = new UserApiClient(config.get("backend.base.url"));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (orchestrator.isRunning()) {
                orchestrator.stopAll(line -> { });
            }
        }, "shutdown-hook"));

        stage.setTitle("Acoustic CCTV - Control Panel");
        showLogin();
        stage.show();
    }

    // ---------- Login ----------

    private void showLogin() {
        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 12px;");
        HBox statusBar = new HBox(statusLabel);
        statusBar.setAlignment(Pos.CENTER);
        statusBar.setPadding(new Insets(12, 0, 0, 0));

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");
        root.setTop(statusBar);

        LoginView loginView = new LoginView(userApiClient, this::onLoginSuccess);
        root.setCenter(loginView.buildSplitLoginScreen(() ->
                root.setCenter(loginView.buildSplitRegisterScreen(() -> showLogin()))));

        setScene(root);
        ensureBackendStarted(statusLabel);
    }

    private void ensureBackendStarted(Label statusLabel) {
        if (backendReady) {
            statusLabel.setText("");
            return;
        }
        if (backendStarting) {
            statusLabel.setText("Starting backend...");
            return;
        }
        backendStarting = true;
        statusLabel.setText("Starting backend...");
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                return orchestrator.startBackendOnly(line -> System.out.println("[backend-boot] " + line));
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            backendStarting = false;
            backendReady = task.getValue();
            statusLabel.setText(backendReady ? ""
                    : "Backend failed to start - check it isn't already running elsewhere.");
        }));
        task.setOnFailed(e -> Platform.runLater(() -> {
            backendStarting = false;
            statusLabel.setText("Backend failed to start.");
        }));
        new Thread(task, "backend-boot-task").start();
    }

    private void onLoginSuccess(UserApiClient.LoggedInUser user) {
        currentUser = user;
        if ("ADMIN".equalsIgnoreCase(user.role())) {
            showAdminDashboard();
        } else {
            ResidentView residentView = new ResidentView(apiClient, userApiClient,
                    config.get("backend.dir"), user, this::showLogin);
            setScene(residentView.build());
        }
    }

    private void setScene(Node root) {
        Scene scene = new Scene((javafx.scene.Parent) root, 1320, 820);
        scene.getStylesheets().add(getClass().getResource("/dashboard.css").toExternalForm());
        stage.setScene(scene);
    }

    // ---------- Admin dashboard (sidebar layout) ----------

    private void showAdminDashboard() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");
        root.setLeft(buildSidebar());

        BorderPane contentArea = new BorderPane();
        contentArea.setTop(buildTopBar());
        contentArea.setBottom(buildLogPanel());

        sectionContainer = new StackPane();
        sectionContainer.setPadding(new Insets(0));
        contentArea.setCenter(sectionContainer);
        root.setCenter(contentArea);

        showSection(sidebarButtons.get(0), buildMonitorSection());

        setScene(root);

        if (!livePollingStarted) {
            startLivePolling();
            livePollingStarted = true;
        }

        // masterEvents already has whatever was loaded during this admin
        // session (e.g. re-entering the dashboard after a sign-out/back-in
        // without a full app restart), so show it immediately - then always
        // kick off a background refresh so it's never stale for long.
        refreshEvents();
    }

    private VBox buildSidebar() {
        Label logo = new Label("Acoustic CCTV");
        logo.getStyleClass().add("sidebar-logo");

        sidebarButtons.clear();
        Button monitorBtn = sidebarButton("Live Monitoring");
        Button recordingsBtn = sidebarButton("Recordings");
        Button enrollBtn = sidebarButton("Enroll Person");
        Button approvalsBtn = sidebarButton("Approvals");
        Button profileBtn = sidebarButton("My Profile");
        sidebarButtons.add(monitorBtn);
        sidebarButtons.add(recordingsBtn);
        sidebarButtons.add(enrollBtn);
        sidebarButtons.add(approvalsBtn);
        sidebarButtons.add(profileBtn);

        monitorBtn.setOnAction(e -> {
            showSection(monitorBtn, buildMonitorSection());
            refreshEvents();
        });
        recordingsBtn.setOnAction(e -> {
            showSection(recordingsBtn, buildRecordingsSection());
            refreshEvents();
        });
        enrollBtn.setOnAction(e -> showSection(enrollBtn, buildEnrollSection()));
        approvalsBtn.setOnAction(e -> showSection(approvalsBtn, buildApprovalsSection()));
        profileBtn.setOnAction(e -> showSection(profileBtn, buildProfileSection()));

        VBox nav = new VBox(4, monitorBtn, recordingsBtn, enrollBtn, approvalsBtn, profileBtn);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label userLabel = new Label(currentUser != null ? currentUser.displayName() : "");
        userLabel.setStyle("-fx-text-fill: #f3f4f6; -fx-font-size: 12px; -fx-font-weight: bold;");
        Button signOutButton = new Button("Sign Out");
        signOutButton.getStyleClass().add("secondary-button");
        signOutButton.setOnAction(e -> showLogin());
        VBox footer = new VBox(8, userLabel, signOutButton);
        footer.setPadding(new Insets(12, 8, 0, 8));

        VBox sidebar = new VBox(8, logo, nav, spacer, footer);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(200);
        sidebar.setMinWidth(200);
        return sidebar;
    }

    private Button sidebarButton(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("sidebar-nav-button");
        return b;
    }

    private void showSection(Button activeButton, Node content) {
        for (Button b : sidebarButtons) {
            b.getStyleClass().remove("sidebar-nav-button-active");
            if (!b.getStyleClass().contains("sidebar-nav-button")) {
                b.getStyleClass().add("sidebar-nav-button");
            }
        }
        activeButton.getStyleClass().remove("sidebar-nav-button");
        activeButton.getStyleClass().add("sidebar-nav-button-active");
        sectionContainer.getChildren().setAll(content);
    }

    private HBox buildTopBar() {
        Label iconBadge = new Label("\u25C9");
        iconBadge.getStyleClass().add("hero-icon-badge");
        iconBadge.setAlignment(Pos.CENTER);

        Label title = new Label("Control Panel");
        title.getStyleClass().add("hero-title");
        Label subtitle = new Label("Acoustic-triggered CCTV - live system control");
        subtitle.getStyleClass().add("hero-subtitle");
        VBox titleBox = new VBox(2, title, subtitle);

        HBox titleGroup = new HBox(14, iconBadge, titleBox);
        titleGroup.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label statusDot = new Label("\u25CF");
        statusDot.getStyleClass().add("status-dot");
        Label statusText = new Label();
        statusText.getStyleClass().add("status-text");
        HBox statusPill = new HBox(6, statusDot, statusText);
        statusPill.getStyleClass().add("status-pill");
        statusPill.setAlignment(Pos.CENTER_LEFT);

        powerToggle = new ToggleButton();
        powerToggle.getStyleClass().add("power-button");
        powerToggle.setPrefWidth(170);
        boolean alreadyRunning = orchestrator.isFullyRunning();
        powerToggle.setSelected(alreadyRunning);
        powerToggle.setText(alreadyRunning ? "Stop System" : "Start System");
        powerToggle.setOnAction(e -> onPowerToggle());

        Runnable syncStatusPill = () -> {
            boolean running = powerToggle.isSelected();
            statusPill.getStyleClass().removeAll("status-pill-on", "status-pill-off");
            statusPill.getStyleClass().add(running ? "status-pill-on" : "status-pill-off");
            statusText.setText(running ? "System Online" : "System Offline");
        };
        syncStatusPill.run();
        powerToggle.selectedProperty().addListener((obs, old, sel) -> syncStatusPill.run());

        Button refreshButton = new Button("Refresh Events");
        refreshButton.getStyleClass().add("secondary-button");
        refreshButton.setOnAction(e -> refreshEvents());

        HBox actionGroup = new HBox(14, statusPill, powerToggle, refreshButton);
        actionGroup.setAlignment(Pos.CENTER_RIGHT);

        HBox bar = new HBox(15, titleGroup, spacer, actionGroup);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("top-bar-hero");
        return bar;
    }

    private void onPowerToggle() {
        if (powerToggle.isSelected()) {
            powerToggle.setText("Starting...");
            powerToggle.setDisable(true);
            Task<Boolean> startTask = new Task<>() {
                @Override
                protected Boolean call() {
                    return orchestrator.startAll(DashboardApp.this::appendLog);
                }
            };
            startTask.setOnSucceeded(e -> Platform.runLater(() -> {
                boolean ok = startTask.getValue();
                powerToggle.setDisable(false);
                powerToggle.setSelected(ok);
                powerToggle.setText(ok ? "Stop System" : "Start System");
                if (!ok) {
                    appendLog("Startup failed - check the log above. Click Start again to retry.");
                }
            }));
            new Thread(startTask, "start-task").start();
        } else {
            powerToggle.setText("Stopping...");
            powerToggle.setDisable(true);
            Task<Void> stopTask = new Task<>() {
                @Override
                protected Void call() {
                    orchestrator.stopMonitoring(DashboardApp.this::appendLog);
                    return null;
                }
            };
            stopTask.setOnSucceeded(e -> Platform.runLater(() -> {
                powerToggle.setDisable(false);
                powerToggle.setText("Start System");
            }));
            new Thread(stopTask, "stop-task").start();
        }
    }

    // ---------- Live Monitoring section ----------

    private HBox buildMonitorSection() {
        VBox eventsPane = buildEventListPane();
        VBox detailCard = buildDetailCard();
        VBox liveFeedPanel = buildLiveFeedPanel();

        HBox center = new HBox(16, eventsPane, detailCard, liveFeedPanel);
        center.setPadding(new Insets(16));
        HBox.setHgrow(eventsPane, Priority.ALWAYS);
        return center;
    }

    private VBox buildEventListPane() {
        Label header = new Label("Events");
        header.getStyleClass().add("panel-header");

        eventList = new ListView<>();
        eventList.getStyleClass().add("event-list");
        eventList.setCellFactory(list -> new EventDisplay.EventCell());
        eventList.setItems(masterEvents);
        eventList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                showEventDetail(selected);
            }
        });
        VBox.setVgrow(eventList, Priority.ALWAYS);

        VBox pane = new VBox(6, header, eventList);
        pane.setPrefWidth(320);
        return pane;
    }

    private VBox buildDetailCard() {
        detailTitle = new Label("Select an event");
        detailTitle.getStyleClass().add("detail-title");

        detailBadge = new Label();
        detailBadge.getStyleClass().add("badge");
        detailBadge.setVisible(false);

        detailLine1 = new Label();
        detailLine1.getStyleClass().add("detail-line");
        detailLine2 = new Label();
        detailLine2.getStyleClass().add("detail-line");

        thumbnailView = new ImageView();
        thumbnailView.setFitWidth(320);
        thumbnailView.setPreserveRatio(true);

        playButton = new Button("Play Recording");
        playButton.getStyleClass().add("play-button");
        playButton.setDisable(true);
        playButton.setOnAction(e -> openVideoExternally(selectedEvent));

        VBox card = new VBox(10, detailTitle, detailBadge, detailLine1, detailLine2, thumbnailView, playButton);
        card.getStyleClass().add("panel-card");
        card.setPrefWidth(360);
        return card;
    }

    private VBox buildLiveFeedPanel() {
        liveFeedView = new ImageView();
        liveFeedView.setFitWidth(320);
        liveFeedView.setPreserveRatio(true);
        liveFeedView.setVisible(false);

        liveFeedPlaceholder = new Label("Camera idle - waiting for a trigger");
        liveFeedPlaceholder.getStyleClass().add("live-feed-placeholder");

        StackPane liveFeedContainer = new StackPane(liveFeedPlaceholder, liveFeedView);
        liveFeedContainer.getStyleClass().add("live-feed-box");
        liveFeedContainer.setPrefSize(320, 240);
        liveFeedContainer.setMinSize(320, 240);

        Label cameraHeader = new Label("Live Camera Feed");
        cameraHeader.getStyleClass().add("panel-header");

        Label liveDot = new Label("");
        liveDot.setStyle("-fx-text-fill: #22c55e; -fx-font-weight: bold; -fx-font-size: 11px;");
        powerToggle.selectedProperty().addListener((obs, old, running) ->
                liveDot.setText(running ? "\u25CF LIVE" : ""));

        HBox cameraHeaderRow = new HBox(8, cameraHeader, liveDot);
        cameraHeaderRow.setAlignment(Pos.CENTER_LEFT);

        Label audioHeader = new Label("Live Audio Level");
        audioHeader.getStyleClass().add("panel-header");

        audioCanvas = new Canvas(320, 90);
        GraphicsContext gc = audioCanvas.getGraphicsContext2D();
        gc.setFill(Color.web("#0a0b10"));
        gc.fillRect(0, 0, audioCanvas.getWidth(), audioCanvas.getHeight());

        VBox box = new VBox(6, cameraHeaderRow, liveFeedContainer, audioHeader, audioCanvas);
        box.getStyleClass().add("panel-card");
        box.setPrefWidth(360);
        return box;
    }

    private void startLivePolling() {
        File liveFrameFile = new File(config.get("infer.dir"), config.get("infer.live_frame"));
        File liveAudioFile = new File(config.get("infer.dir"), config.get("infer.live_audio"));

        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(400), e -> {
            if (liveFeedView != null) {
                pollLiveFrame(liveFrameFile);
            }
            if (audioCanvas != null) {
                pollLiveAudio(liveAudioFile);
            }
        }));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    private void pollLiveFrame(File liveFrameFile) {
        if (!liveFrameFile.exists()) {
            return;
        }
        long modified = liveFrameFile.lastModified();
        if (modified == lastLiveFrameModified) {
            return;
        }
        try {
            Image img = new Image(liveFrameFile.toURI().toString());
            if (!img.isError() && img.getWidth() > 0) {
                lastLiveFrameModified = modified;
                liveFeedView.setImage(img);
                liveFeedView.setVisible(true);
                liveFeedPlaceholder.setVisible(false);
            }
        } catch (Exception ex) {
            // Retry next poll.
        }
    }

    private void pollLiveAudio(File liveAudioFile) {
        if (!liveAudioFile.exists()) {
            return;
        }
        long modified = liveAudioFile.lastModified();
        if (modified == lastLiveAudioModified) {
            return;
        }
        try {
            LiveAudioFrame frame = audioMapper.readValue(liveAudioFile, LiveAudioFrame.class);
            lastLiveAudioModified = modified;
            appendToAudioHistory(frame);
            drawAudioWaveform();
        } catch (Exception ex) {
            // Retry next poll.
        }
    }

    private void appendToAudioHistory(LiveAudioFrame frame) {
        lastAudioLabel = frame.label;
        lastAudioConfidence = frame.confidence;

        List<Double> points = frame.points;
        if (points == null || points.isEmpty()) {
            return;
        }
        boolean triggered = Boolean.TRUE.equals(frame.triggered);
        int step = Math.max(1, points.size() / POINTS_PER_WINDOW_IN_HISTORY);
        for (int i = 0; i < points.size(); i += step) {
            audioHistory.addLast(points.get(i));
            audioTriggerHistory.addLast(triggered);
        }
        while (audioHistory.size() > HISTORY_MAX_POINTS) {
            audioHistory.removeFirst();
            audioTriggerHistory.removeFirst();
        }
    }

    private void drawAudioWaveform() {
        GraphicsContext gc = audioCanvas.getGraphicsContext2D();
        double w = audioCanvas.getWidth();
        double h = audioCanvas.getHeight();
        gc.setFill(Color.web("#0a0b10"));
        gc.fillRect(0, 0, w, h);

        if (audioHistory.isEmpty()) {
            return;
        }

        Double[] amps = audioHistory.toArray(new Double[0]);
        Boolean[] triggers = audioTriggerHistory.toArray(new Boolean[0]);
        int n = amps.length;
        double barWidth = w / HISTORY_MAX_POINTS;
        double startX = w - n * barWidth;

        for (int i = 0; i < n; i++) {
            double amp = Math.max(0.0, Math.min(1.0, amps[i]));
            double barHeight = amp * h;
            gc.setFill(Boolean.TRUE.equals(triggers[i]) ? Color.web("#ef4444") : Color.web("#22c55e"));
            gc.fillRect(startX + i * barWidth, h - barHeight, Math.max(1, barWidth - 0.5), barHeight);
        }

        gc.setFill(Color.WHITE);
        String label = lastAudioLabel != null ? EventDisplay.friendlyLabel(lastAudioLabel) : "-";
        String conf = lastAudioConfidence != null ? String.format("  %.0f%%", lastAudioConfidence * 100) : "";
        gc.fillText(label + conf, 6, 13);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class LiveAudioFrame {
        public List<Double> points;
        public String label;
        public Double confidence;
        public Boolean triggered;
    }

    // ---------- Recordings section ----------

    private ScrollPane buildRecordingsSection() {
        recordingsGallery = new FlowPane(16, 16);
        recordingsGallery.setPadding(new Insets(16));
        populateRecordingsGallery(masterEvents);
        ScrollPane scrollPane = new ScrollPane(recordingsGallery);
        scrollPane.setFitToWidth(true);
        return scrollPane;
    }

    private void populateRecordingsGallery(List<EventRecord> events) {
        if (recordingsGallery == null) {
            return;
        }
        recordingsGallery.getChildren().clear();
        for (EventRecord event : events) {
            if (event.videoPath != null) {
                recordingsGallery.getChildren().add(buildRecordingCard(event));
            }
        }
        if (recordingsGallery.getChildren().isEmpty()) {
            Label empty = new Label("No recordings yet.");
            empty.getStyleClass().add("live-feed-placeholder");
            recordingsGallery.getChildren().add(empty);
        }
    }

    private VBox buildRecordingCard(EventRecord event) {
        ImageView thumb = new ImageView();
        thumb.setFitWidth(180);
        thumb.setFitHeight(120);
        thumb.setPreserveRatio(false);
        if (event.imagePath != null) {
            File imageFile = new File(config.get("backend.dir"), event.imagePath);
            if (imageFile.exists()) {
                try {
                    thumb.setImage(new Image(imageFile.toURI().toString()));
                } catch (Exception ignored) { }
            }
        }

        Label caption = new Label(EventDisplay.friendlyLabel(event.label) + "\n" + EventDisplay.formatTime(event.detectedAt));
        caption.setStyle("-fx-font-size: 11px; -fx-text-fill: #9ca3af;");

        VBox card = new VBox(6, thumb, caption);
        card.getStyleClass().add("panel-card");
        card.setPrefWidth(196);
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> openVideoExternally(event));
        return card;
    }

    // ---------- Enroll section ----------

    private VBox buildEnrollSection() {
        Label enrollTitle = new Label("Enroll a Resident / Known Face");
        enrollTitle.getStyleClass().add("detail-title");

        Label enrollSubtitle = new Label(
                "Add someone to the face-recognition database so the system can identify them.");
        enrollSubtitle.getStyleClass().add("detail-line");
        enrollSubtitle.setWrapText(true);

        TextField nameField = new TextField();
        nameField.setPromptText("Full name");
        TextField flatField = new TextField();
        flatField.setPromptText("Flat / house number (optional)");
        ComboBox<String> statusBox = new ComboBox<>();
        statusBox.getItems().addAll("resident", "guest", "staff");
        statusBox.setValue("resident");

        Label photoLabel = new Label("No photo chosen");
        Button chooseButton = new Button("Choose Photo...");
        chooseButton.getStyleClass().add("secondary-button");
        File[] chosenFile = new File[1];
        chooseButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png"));
            File file = chooser.showOpenDialog(chooseButton.getScene().getWindow());
            if (file != null) {
                chosenFile[0] = file;
                photoLabel.setText(file.getName());
            }
        });

        Label resultLabel = new Label();
        resultLabel.setWrapText(true);

        Button enrollButton = new Button("Enroll Person");
        enrollButton.getStyleClass().add("play-button");
        enrollButton.setOnAction(e -> {
            if (nameField.getText().isBlank() || chosenFile[0] == null) {
                resultLabel.setText("Name and a photo are both required.");
                resultLabel.setStyle("-fx-text-fill: #dc2626;");
                return;
            }
            enrollButton.setDisable(true);
            FaceEnrollmentClient client = new FaceEnrollmentClient(config.get("face.base.url"));
            Task<String> task = new Task<>() {
                @Override
                protected String call() throws Exception {
                    return client.enroll(nameField.getText(), flatField.getText(), statusBox.getValue(),
                            chosenFile[0].toPath());
                }
            };
            task.setOnSucceeded(ev -> Platform.runLater(() -> {
                enrollButton.setDisable(false);
                resultLabel.setText("Enrolled successfully.");
                resultLabel.setStyle("-fx-text-fill: #16a34a;");
                appendLog("Enrolled new person: " + nameField.getText());
            }));
            task.setOnFailed(ev -> Platform.runLater(() -> {
                enrollButton.setDisable(false);
                resultLabel.setText("Enrollment failed: " + task.getException().getMessage());
                resultLabel.setStyle("-fx-text-fill: #dc2626;");
            }));
            new Thread(task, "enroll-task").start();
        });

        VBox form = new VBox(12,
                enrollTitle, enrollSubtitle,
                labeledField("Name", nameField),
                labeledField("Flat / House Number", flatField),
                labeledField("Status", statusBox),
                new HBox(10, chooseButton, photoLabel),
                enrollButton,
                resultLabel);
        form.getStyleClass().add("panel-card");
        form.setPrefWidth(420);

        Label vehicleNote = new Label(
                "Vehicle/plate-to-owner enrollment isn't available yet - needs anpr-service's " +
                        "enrollment endpoint to be wired in.");
        vehicleNote.getStyleClass().add("live-feed-placeholder");
        vehicleNote.setWrapText(true);
        vehicleNote.setMaxWidth(420);

        VBox wrapper = new VBox(20, form, vehicleNote);
        wrapper.setPadding(new Insets(20));
        return wrapper;
    }

    // ---------- Approvals section ----------

    private VBox buildApprovalsSection() {
        ListView<UserApiClient.PendingRequest> pendingList = new ListView<>();
        pendingList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(UserApiClient.PendingRequest item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Label info = new Label(item.displayName() + " (" + item.username() + ")  -  Flat: " +
                        (item.flatNumber().isBlank() ? "-" : item.flatNumber()) + "  -  requested " + item.requestedAt());
                info.setStyle("-fx-font-size: 12px; -fx-text-fill: #f3f4f6;");
                HBox.setHgrow(info, Priority.ALWAYS);

                Button approveButton = new Button("Approve");
                approveButton.getStyleClass().add("play-button");
                Button rejectButton = new Button("Reject");
                rejectButton.getStyleClass().add("secondary-button");
                approveButton.setOnAction(e -> handleApproval(item.id(), true, pendingList));
                rejectButton.setOnAction(e -> handleApproval(item.id(), false, pendingList));

                HBox row = new HBox(10, info, approveButton, rejectButton);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(6));
                setGraphic(row);
            }
        });
        VBox.setVgrow(pendingList, Priority.ALWAYS);

        Button refreshButton = new Button("Refresh Requests");
        refreshButton.getStyleClass().add("secondary-button");
        refreshButton.setOnAction(e -> loadPendingRequests(pendingList));

        VBox box = new VBox(10, refreshButton, pendingList);
        box.setPadding(new Insets(16));
        VBox.setVgrow(pendingList, Priority.ALWAYS);

        loadPendingRequests(pendingList);
        return box;
    }

    private void loadPendingRequests(ListView<UserApiClient.PendingRequest> pendingList) {
        Task<List<UserApiClient.PendingRequest>> task = new Task<>() {
            @Override
            protected List<UserApiClient.PendingRequest> call() throws Exception {
                return userApiClient.pendingRequests();
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> pendingList.getItems().setAll(task.getValue())));
        task.setOnFailed(e -> Platform.runLater(() ->
                appendLog("Failed to load pending requests: " + task.getException().getMessage())));
        new Thread(task, "pending-requests-task").start();
    }

    private void handleApproval(String id, boolean approve, ListView<UserApiClient.PendingRequest> pendingList) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                if (approve) {
                    userApiClient.approve(id);
                } else {
                    userApiClient.reject(id);
                }
                return null;
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            appendLog((approve ? "Approved" : "Rejected") + " account request.");
            loadPendingRequests(pendingList);
        }));
        task.setOnFailed(e -> Platform.runLater(() ->
                appendLog("Action failed: " + task.getException().getMessage())));
        new Thread(task, "approval-action-task").start();
    }

    // ---------- Profile section ----------

    private ScrollPane buildProfileSection() {
        ProfileView profileView = new ProfileView(userApiClient, config.get("backend.dir"));
        VBox card = profileView.build(currentUser, updated -> {
            currentUser = updated;
            appendLog("Profile updated.");
        });
        VBox wrapper = new VBox(card);
        wrapper.setPadding(new Insets(20));
        ScrollPane scrollPane = new ScrollPane(wrapper);
        scrollPane.setFitToWidth(true);
        return scrollPane;
    }

    private VBox labeledField(String labelText, Control field) {
        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: #9ca3af; -fx-font-weight: bold;");
        return new VBox(4, label, field);
    }

    // ---------- Log panel ----------

    private VBox buildLogPanel() {
        Label header = new Label("Console");
        header.getStyleClass().add("panel-header");
        header.setPadding(new Insets(10, 16, 0, 16));

        logArea = new TextArea();
        logArea.getStyleClass().add("console-area");
        logArea.setEditable(false);
        logArea.setPrefRowCount(9);

        VBox box = new VBox(header, logArea);
        box.setPadding(new Insets(0, 16, 16, 16));
        return box;
    }

    // ---------- Data loading ----------

    private void refreshEvents() {
        Task<List<EventRecord>> task = new Task<>() {
            @Override
            protected List<EventRecord> call() throws Exception {
                return apiClient.listEvents();
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            // Only show events whose verification has actually finished
            // (MATCH/NO_MATCH) - events still mid-capture, too-quiet-to-act-on,
            // or a scream's transient pre-capture ALERT state are left out of
            // both the list and the recordings gallery.
            List<EventRecord> verified = task.getValue().stream()
                    .filter(EventDisplay::isVerified)
                    .collect(java.util.stream.Collectors.toList());
            masterEvents.setAll(verified);
            if (recordingsGallery != null) {
                populateRecordingsGallery(masterEvents);
            }
            appendLog("Loaded " + verified.size() + " verified event(s).");
        }));
        task.setOnFailed(e -> Platform.runLater(() ->
                appendLog("Failed to load events: " + task.getException().getMessage() +
                        " (is the backend running?)")));
        new Thread(task, "refresh-events-task").start();
    }

    private void showEventDetail(EventRecord event) {
        selectedEvent = event;
        detailTitle.setText(EventDisplay.friendlyLabel(event.label));

        String[] status = EventDisplay.friendlyStatus(event);
        detailBadge.setText(status[0]);
        detailBadge.getStyleClass().removeIf(c -> c.startsWith("badge-"));
        detailBadge.getStyleClass().add(status[1]);
        detailBadge.setVisible(true);

        detailLine1.setText("Detected " + EventDisplay.formatTime(event.detectedAt));

        if (event.matchedIdentity != null && "MATCH".equals(event.status)) {
            String who = "PLATE".equals(event.verificationType) ? "Vehicle plate: " : "Identified as: ";
            detailLine2.setText(who + event.matchedIdentity);
        } else {
            detailLine2.setText("");
        }

        EventDisplay.loadThumbnail(thumbnailView, event, config.get("backend.dir"));
        playButton.setDisable(event.videoPath == null);
    }

    private void openVideoExternally(EventRecord event) {
        if (event == null || event.videoPath == null) {
            return;
        }
        File videoFile = new File(config.get("backend.dir"), event.videoPath);
        if (!videoFile.exists()) {
            appendLog("Video file not found on disk: " + videoFile.getAbsolutePath());
            return;
        }
        if (!Desktop.isDesktopSupported()) {
            appendLog("Can't open an external player on this system (Desktop API unsupported).");
            return;
        }
        Task<Void> openTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Desktop.getDesktop().open(videoFile);
                return null;
            }
        };
        openTask.setOnFailed(e -> Platform.runLater(() ->
                appendLog("Could not open video: " + openTask.getException().getMessage())));
        new Thread(openTask, "open-video-task").start();
    }

    private void appendLog(String line) {
        Platform.runLater(() -> logArea.appendText(line + "\n"));
    }

    public static void main(String[] args) {
        launch(args);
    }
}