package com.acousticcctv.dashboard;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.function.Consumer;

/** Editable profile card - photo, name, flat number - shared by both
 * ADMIN and RESIDENT views. */
public class ProfileView {

    private final UserApiClient userApiClient;
    private final String backendDir;

    public ProfileView(UserApiClient userApiClient, String backendDir) {
        this.userApiClient = userApiClient;
        this.backendDir = backendDir;
    }

    public VBox build(UserApiClient.LoggedInUser user, Consumer<UserApiClient.LoggedInUser> onUpdated) {
        Label title = new Label("My Profile");
        title.getStyleClass().add("detail-title");

        ImageView photoView = new ImageView();
        photoView.setFitWidth(120);
        photoView.setFitHeight(120);
        photoView.setPreserveRatio(false);
        photoView.setClip(new Circle(60, 60, 60));
        loadPhoto(photoView, user.profilePhotoPath());

        Button changePhotoButton = new Button("Change Photo...");
        changePhotoButton.getStyleClass().add("secondary-button");
        File[] chosenFile = new File[1];
        changePhotoButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png"));
            File file = chooser.showOpenDialog(changePhotoButton.getScene().getWindow());
            if (file != null) {
                chosenFile[0] = file;
                photoView.setImage(new Image(file.toURI().toString()));
            }
        });

        VBox photoBox = new VBox(10, photoView, changePhotoButton);
        photoBox.setAlignment(Pos.CENTER);

        TextField nameField = new TextField(user.displayName());
        TextField flatField = new TextField(user.flatNumber());
        boolean isAdmin = "ADMIN".equalsIgnoreCase(user.role());
        flatField.setDisable(isAdmin);

        Label usernameLabel = new Label("Username: " + user.username());
        usernameLabel.getStyleClass().add("detail-line");
        Label roleLabel = new Label("Role: " + (isAdmin ? "Administrator" : "Resident"));
        roleLabel.getStyleClass().add("detail-line");

        Label resultLabel = new Label();
        resultLabel.setWrapText(true);

        Button saveButton = new Button("Save Changes");
        saveButton.getStyleClass().add("play-button");
        saveButton.setOnAction(e -> {
            saveButton.setDisable(true);
            Task<UserApiClient.ProfileUpdateResult> task = new Task<>() {
                @Override
                protected UserApiClient.ProfileUpdateResult call() throws Exception {
                    return userApiClient.updateProfile(user.id(), nameField.getText(),
                            chosenFile[0] != null ? chosenFile[0].toPath() : null);
                }
            };
            task.setOnSucceeded(ev -> Platform.runLater(() -> {
                saveButton.setDisable(false);
                resultLabel.setText("Saved.");
                resultLabel.setStyle("-fx-text-fill: #16a34a;");
                UserApiClient.ProfileUpdateResult result = task.getValue();
                UserApiClient.LoggedInUser updated = new UserApiClient.LoggedInUser(
                        user.id(), user.username(), user.role(),
                        result.displayName() != null ? result.displayName() : user.displayName(),
                        user.flatNumber(),
                        result.profilePhotoPath() != null && !result.profilePhotoPath().isBlank()
                                ? result.profilePhotoPath() : user.profilePhotoPath());
                onUpdated.accept(updated);
            }));
            task.setOnFailed(ev -> Platform.runLater(() -> {
                saveButton.setDisable(false);
                resultLabel.setText("Save failed: " + task.getException().getMessage());
                resultLabel.setStyle("-fx-text-fill: #dc2626;");
            }));
            new Thread(task, "profile-update-task").start();
        });

        VBox form = new VBox(12, title, photoBox, usernameLabel, roleLabel,
                labeledField("Name", nameField),
                labeledField("Flat / House Number", flatField),
                saveButton, resultLabel);
        form.getStyleClass().add("panel-card");
        form.setPrefWidth(380);

        VBox passwordCard = buildPasswordCard(user);

        VBox wrapper = new VBox(20, form, passwordCard);
        return wrapper;
    }

    private VBox buildPasswordCard(UserApiClient.LoggedInUser user) {
        Label title = new Label("Change Password");
        title.getStyleClass().add("detail-title");

        PasswordField currentField = new PasswordField();
        currentField.setPromptText("Current password");
        PasswordField newField = new PasswordField();
        newField.setPromptText("New password (min. 6 characters)");
        PasswordField confirmField = new PasswordField();
        confirmField.setPromptText("Confirm new password");

        Label resultLabel = new Label();
        resultLabel.setWrapText(true);

        Button changeButton = new Button("Update Password");
        changeButton.getStyleClass().add("play-button");
        changeButton.setOnAction(e -> {
            if (currentField.getText().isBlank() || newField.getText().isBlank()) {
                resultLabel.setText("Enter your current and new password.");
                resultLabel.setStyle("-fx-text-fill: #dc2626;");
                return;
            }
            if (!newField.getText().equals(confirmField.getText())) {
                resultLabel.setText("New password and confirmation don't match.");
                resultLabel.setStyle("-fx-text-fill: #dc2626;");
                return;
            }
            changeButton.setDisable(true);
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    userApiClient.changePassword(user.id(), currentField.getText(), newField.getText());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> Platform.runLater(() -> {
                changeButton.setDisable(false);
                currentField.clear();
                newField.clear();
                confirmField.clear();
                resultLabel.setText("Password updated.");
                resultLabel.setStyle("-fx-text-fill: #16a34a;");
            }));
            task.setOnFailed(ev -> Platform.runLater(() -> {
                changeButton.setDisable(false);
                resultLabel.setText(task.getException().getMessage());
                resultLabel.setStyle("-fx-text-fill: #dc2626;");
            }));
            new Thread(task, "change-password-task").start();
        });

        VBox card = new VBox(12, title,
                labeledField("Current Password", currentField),
                labeledField("New Password", newField),
                labeledField("Confirm New Password", confirmField),
                changeButton, resultLabel);
        card.getStyleClass().add("panel-card");
        card.setPrefWidth(380);
        return card;
    }

    private void loadPhoto(ImageView view, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        File file = new File(backendDir, relativePath);
        if (file.exists()) {
            try {
                view.setImage(new Image(file.toURI().toString()));
            } catch (Exception ignored) { }
        }
    }

    private VBox labeledField(String labelText, Control field) {
        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: #9ca3af; -fx-font-weight: bold;");
        return new VBox(4, label, field);
    }
}