package com.acousticcctv.dashboard;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * View a RESIDENT sees after logging in - their own recognized events, the
 * recordings that go with them, and an editable profile (with password
 * change). Mirrors the admin dashboard's sidebar layout so the same kind of
 * data is reachable the same way, just scoped to this one resident: no
 * process control, no console, no other residents' data.
 */
public class ResidentView {

    private final EventApiClient eventApiClient;
    private final UserApiClient userApiClient;
    private final String backendDir;
    private UserApiClient.LoggedInUser user;
    private final Runnable onLogout;

    private final ObservableList<EventRecord> masterEvents = FXCollections.observableArrayList();
    private final List<Button> sidebarButtons = new ArrayList<>();
    private StackPane sectionContainer;

    private ListView<EventRecord> eventList;
    private EventRecord selectedEvent;
    private Label detailTitle;
    private Label detailBadge;
    private Label detailLine1;
    private Label detailLine2;
    private ImageView thumbnailView;
    private Button playButton;
    private FlowPane recordingsGallery;
    private Label headerTitle;
    private Label sidebarUserLabel;

    public ResidentView(EventApiClient eventApiClient, UserApiClient userApiClient, String backendDir,
                        UserApiClient.LoggedInUser user, Runnable onLogout) {
        this.eventApiClient = eventApiClient;
        this.userApiClient = userApiClient;
        this.backendDir = backendDir;
        this.user = user;
        this.onLogout = onLogout;
    }

    public BorderPane build() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");
        root.setLeft(buildSidebar());

        BorderPane contentArea = new BorderPane();
        contentArea.setTop(buildTopBar());

        sectionContainer = new StackPane();
        contentArea.setCenter(sectionContainer);
        root.setCenter(contentArea);

        showSection(sidebarButtons.get(0), buildActivitySection());
        refreshEvents();
        return root;
    }

    // ---------- Sidebar / top bar ----------

    private VBox buildSidebar() {
        Label logo = new Label("Acoustic CCTV");
        logo.getStyleClass().add("sidebar-logo");

        sidebarButtons.clear();
        Button activityBtn = sidebarButton("My Activity");
        Button recordingsBtn = sidebarButton("My Recordings");
        Button profileBtn = sidebarButton("My Profile");
        sidebarButtons.add(activityBtn);
        sidebarButtons.add(recordingsBtn);
        sidebarButtons.add(profileBtn);

        activityBtn.setOnAction(e -> {
            showSection(activityBtn, buildActivitySection());
            refreshEvents();
        });
        recordingsBtn.setOnAction(e -> {
            showSection(recordingsBtn, buildRecordingsSection());
            refreshEvents();
        });
        profileBtn.setOnAction(e -> showSection(profileBtn, buildProfileSection()));

        VBox nav = new VBox(4, activityBtn, recordingsBtn, profileBtn);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        sidebarUserLabel = new Label(user.displayName());
        sidebarUserLabel.setStyle("-fx-text-fill: #f3f4f6; -fx-font-size: 12px; -fx-font-weight: bold;");
        Button signOutButton = new Button("Sign Out");
        signOutButton.getStyleClass().add("secondary-button");
        signOutButton.setOnAction(e -> onLogout.run());
        VBox footer = new VBox(8, sidebarUserLabel, signOutButton);
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

        headerTitle = new Label("Welcome, " + user.displayName());
        headerTitle.getStyleClass().add("hero-title");
        Label subtitle = new Label("Your recognized activity and recordings");
        subtitle.getStyleClass().add("hero-subtitle");
        VBox titleBox = new VBox(2, headerTitle, subtitle);

        HBox titleGroup = new HBox(14, iconBadge, titleBox);
        titleGroup.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshButton = new Button("Refresh");
        refreshButton.getStyleClass().add("secondary-button");
        refreshButton.setOnAction(e -> refreshEvents());

        HBox bar = new HBox(15, titleGroup, spacer, refreshButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("top-bar-hero");
        return bar;
    }

    // ---------- My Activity ----------

    private HBox buildActivitySection() {
        VBox eventsPane = buildEventListPane();
        VBox detailCard = buildDetailCard();

        HBox center = new HBox(16, eventsPane, detailCard);
        center.setPadding(new Insets(16));
        HBox.setHgrow(eventsPane, Priority.ALWAYS);
        return center;
    }

    private VBox buildEventListPane() {
        Label header = new Label("My Events");
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
        pane.setPrefWidth(360);
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
        thumbnailView.setFitWidth(360);
        thumbnailView.setPreserveRatio(true);

        playButton = new Button("Play Recording");
        playButton.getStyleClass().add("play-button");
        playButton.setDisable(true);
        playButton.setOnAction(e -> openVideoExternally(selectedEvent));

        VBox card = new VBox(10, detailTitle, detailBadge, detailLine1, detailLine2, thumbnailView, playButton);
        card.getStyleClass().add("panel-card");
        card.setPrefWidth(400);
        return card;
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
        detailLine2.setText("PLATE".equals(event.verificationType)
                ? "Your vehicle was seen" : "You were recognized");

        EventDisplay.loadThumbnail(thumbnailView, event, backendDir);
        playButton.setDisable(event.videoPath == null);
    }

    // ---------- My Recordings ----------

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
            File imageFile = new File(backendDir, event.imagePath);
            if (imageFile.exists()) {
                try {
                    thumb.setImage(new Image(imageFile.toURI().toString()));
                } catch (Exception ignored) { }
            }
        }

        Label caption = new Label(EventDisplay.friendlyLabel(event.label) + "\n" +
                EventDisplay.formatTime(event.detectedAt));
        caption.setStyle("-fx-font-size: 11px; -fx-text-fill: #9ca3af;");

        VBox card = new VBox(6, thumb, caption);
        card.getStyleClass().add("panel-card");
        card.setPrefWidth(196);
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> openVideoExternally(event));
        return card;
    }

    private void openVideoExternally(EventRecord event) {
        if (event == null || event.videoPath == null) {
            return;
        }
        File videoFile = new File(backendDir, event.videoPath);
        if (!videoFile.exists() || !Desktop.isDesktopSupported()) {
            return;
        }
        Task<Void> openTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Desktop.getDesktop().open(videoFile);
                return null;
            }
        };
        new Thread(openTask, "resident-open-video-task").start();
    }

    // ---------- My Profile ----------

    private ScrollPane buildProfileSection() {
        ProfileView profileView = new ProfileView(userApiClient, backendDir);
        VBox card = profileView.build(user, updated -> {
            this.user = updated;
            headerTitle.setText("Welcome, " + updated.displayName());
            sidebarUserLabel.setText(updated.displayName());
        });
        VBox wrapper = new VBox(card);
        wrapper.setPadding(new Insets(20));
        ScrollPane scrollPane = new ScrollPane(wrapper);
        scrollPane.setFitToWidth(true);
        return scrollPane;
    }

    // ---------- Data loading ----------

    private void refreshEvents() {
        Task<List<EventRecord>> task = new Task<>() {
            @Override
            protected List<EventRecord> call() throws Exception {
                return eventApiClient.listEvents();
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            // Only this resident's own events, and only ones where
            // verification has actually finished (MATCH/NO_MATCH) - same
            // "no unprocessed events" rule the admin view applies.
            List<EventRecord> mine = task.getValue().stream()
                    .filter(EventDisplay::isVerified)
                    .filter(ev -> ev.matchedIdentity != null
                            && ev.matchedIdentity.equalsIgnoreCase(user.displayName()))
                    .collect(Collectors.toList());
            masterEvents.setAll(mine);
            if (recordingsGallery != null) {
                populateRecordingsGallery(masterEvents);
            }
        }));
        new Thread(task, "resident-activity-task").start();
    }
}