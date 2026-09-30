package com.acousticcctv.dashboard;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.function.Consumer;

public class LoginView {

    private final UserApiClient userApiClient;
    private final Consumer<UserApiClient.LoggedInUser> onLoginSuccess;

    public LoginView(UserApiClient userApiClient, Consumer<UserApiClient.LoggedInUser> onLoginSuccess) {
        this.userApiClient = userApiClient;
        this.onLoginSuccess = onLoginSuccess;
    }

    public HBox buildSplitLoginScreen(Runnable onSwitchToRegister) {
        VBox formPanel = buildLoginFormPanel(onSwitchToRegister);
        HBox split = new HBox(buildBrandPanel(), formPanel);
        HBox.setHgrow(formPanel, Priority.ALWAYS);
        return split;
    }

    public HBox buildSplitRegisterScreen(Runnable onSwitchToLogin) {
        VBox formPanel = buildRegisterFormPanel(onSwitchToLogin);
        HBox split = new HBox(buildBrandPanel(), formPanel);
        HBox.setHgrow(formPanel, Priority.ALWAYS);
        return split;
    }

    /** Left branded panel - an original graphic (camera + sound-wave arcs,
     * drawn with JavaFX shapes, no external image file) plus a tagline. */
    private VBox buildBrandPanel() {
        Canvas graphic = buildCameraGraphic();

        Label tagline = new Label("Listening.\nWatching.\nProtecting.");
        tagline.getStyleClass().add("brand-tagline");

        Label subtitle = new Label("Acoustic-triggered CCTV with AI verification");
        subtitle.getStyleClass().add("brand-subtitle");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(320);

        VBox content = new VBox(20, graphic, tagline, subtitle);
        content.setAlignment(Pos.BOTTOM_LEFT);
        content.setPadding(new Insets(40));
        VBox.setVgrow(content, Priority.ALWAYS);

        VBox panel = new VBox(content);
        panel.getStyleClass().add("brand-panel");
        panel.setPrefWidth(440);
        panel.setMinWidth(440);
        return panel;
    }

    private Canvas buildCameraGraphic() {
        Canvas canvas = new Canvas(160, 160);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        gc.setStroke(Color.web("#a78bfa", 0.5));
        gc.setLineWidth(2.5);
        for (int i = 1; i <= 3; i++) {
            double r = 28 + i * 16;
            gc.strokeArc(80 - r, 78 - r, r * 2, r * 2, 25, 130, ArcType.OPEN);
        }

        gc.setFill(Color.web("#f3f4f6"));
        gc.fillRoundRect(42, 62, 76, 48, 16, 16);
        gc.setFill(Color.web("#6366f1"));
        gc.fillOval(58, 70, 32, 32);
        gc.setFill(Color.web("#1a1d29"));
        gc.fillOval(66, 78, 16, 16);
        gc.setFill(Color.web("#f3f4f6"));
        gc.fillOval(70, 82, 5, 5);

        gc.setFill(Color.web("#ef4444"));
        gc.fillOval(102, 68, 9, 9);

        return canvas;
    }

    private VBox buildLoginFormPanel(Runnable onSwitchToRegister) {
        Label title = new Label("Sign In");
        title.getStyleClass().add("detail-title");
        Label subtitle = new Label("Welcome back to the control panel");
        subtitle.getStyleClass().add("detail-line");

        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #dc2626;");
        errorLabel.setWrapText(true);

        Button loginButton = new Button("Sign In");
        loginButton.getStyleClass().add("play-button");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setOnAction(e -> {
            errorLabel.setText("");
            loginButton.setDisable(true);
            Task<UserApiClient.LoggedInUser> task = new Task<>() {
                @Override
                protected UserApiClient.LoggedInUser call() throws Exception {
                    return userApiClient.login(usernameField.getText(), passwordField.getText());
                }
            };
            task.setOnSucceeded(ev -> Platform.runLater(() -> {
                loginButton.setDisable(false);
                onLoginSuccess.accept(task.getValue());
            }));
            task.setOnFailed(ev -> Platform.runLater(() -> {
                loginButton.setDisable(false);
                String msg = task.getException().getMessage();
                errorLabel.setText(msg != null ? msg : "Could not reach the backend - is it running?");
            }));
            new Thread(task, "login-task").start();
        });

        Separator separator = new Separator();

        Button registerLink = new Button("Request an Account");
        registerLink.getStyleClass().add("secondary-button");
        registerLink.setMaxWidth(Double.MAX_VALUE);
        registerLink.setOnAction(e -> onSwitchToRegister.run());

        VBox form = new VBox(12, title, subtitle, usernameField, passwordField, loginButton, errorLabel,
                separator, registerLink);
        form.setMaxWidth(360);
        form.setAlignment(Pos.CENTER_LEFT);

        VBox wrapper = new VBox(form);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setPadding(new Insets(40));
        wrapper.getStyleClass().add("login-form-panel");
        VBox.setVgrow(wrapper, Priority.ALWAYS);
        return wrapper;
    }

    private VBox buildRegisterFormPanel(Runnable onSwitchToLogin) {
        Label title = new Label("Request a Resident Account");
        title.getStyleClass().add("detail-title");

        Label subtitle = new Label(
                "An admin needs to approve this before you can sign in or be recognized by the camera.");
        subtitle.getStyleClass().add("detail-line");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(340);

        TextField usernameField = new TextField();
        usernameField.setPromptText("Choose a username");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Choose a password");
        TextField nameField = new TextField();
        nameField.setPromptText("Your full name");
        TextField flatField = new TextField();
        flatField.setPromptText("Flat / house number (optional)");

        Label photoLabel = new Label("No photo chosen");
        Button chooseButton = new Button("Choose Photo...");
        chooseButton.getStyleClass().add("secondary-button");
        File[] chosenFile = new File[1];
        chooseButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png"));
            File file = chooser.showOpenDialog(chooseButton.getScene().getWindow());
            if (file != null) {
                chosenFile[0] = file;
                photoLabel.setText(file.getName());
            }
        });

        Label statusLabel = new Label();
        statusLabel.setWrapText(true);

        Button submitButton = new Button("Submit Request");
        submitButton.getStyleClass().add("play-button");
        submitButton.setMaxWidth(Double.MAX_VALUE);
        submitButton.setOnAction(e -> {
            if (usernameField.getText().isBlank() || passwordField.getText().isBlank()
                    || nameField.getText().isBlank() || chosenFile[0] == null) {
                statusLabel.setText("Username, password, name, and a photo are all required.");
                statusLabel.setStyle("-fx-text-fill: #dc2626;");
                return;
            }
            submitButton.setDisable(true);
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    userApiClient.requestAccount(usernameField.getText(), passwordField.getText(),
                            nameField.getText(), flatField.getText(), chosenFile[0].toPath());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> Platform.runLater(() -> {
                submitButton.setDisable(false);
                statusLabel.setText("Request submitted - an admin needs to approve it before you can sign in.");
                statusLabel.setStyle("-fx-text-fill: #16a34a;");
            }));
            task.setOnFailed(ev -> Platform.runLater(() -> {
                submitButton.setDisable(false);
                statusLabel.setText(task.getException().getMessage());
                statusLabel.setStyle("-fx-text-fill: #dc2626;");
            }));
            new Thread(task, "register-task").start();
        });

        Button backLink = new Button("Back to Sign In");
        backLink.getStyleClass().add("secondary-button");
        backLink.setMaxWidth(Double.MAX_VALUE);
        backLink.setOnAction(e -> onSwitchToLogin.run());

        VBox form = new VBox(10, title, subtitle, usernameField, passwordField, nameField, flatField,
                new HBox(10, chooseButton, photoLabel),
                submitButton, statusLabel, backLink);
        form.setMaxWidth(380);
        form.setAlignment(Pos.CENTER_LEFT);

        VBox wrapper = new VBox(form);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setPadding(new Insets(40));
        wrapper.getStyleClass().add("login-form-panel");
        VBox.setVgrow(wrapper, Priority.ALWAYS);
        return wrapper;
    }
}