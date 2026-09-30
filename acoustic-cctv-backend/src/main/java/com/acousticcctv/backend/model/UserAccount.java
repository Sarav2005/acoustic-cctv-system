package com.acousticcctv.backend.model;

import java.time.Instant;
import java.util.UUID;

public class UserAccount {

    private String id;
    private String username;
    private String passwordHash;
    private String role;
    private String displayName;
    private String flatNumber;
    private UserStatus status;
    private Instant requestedAt;
    private String pendingPhotoPath;

    // The person's own profile picture, editable from their Profile page
    // - separate from pendingPhotoPath (which is only the enrollment
    // photo submitted with a still-pending request). Stored as a path
    // relative to the backend's working directory, same convention as
    // Event.imagePath/videoPath.
    private String profilePhotoPath;

    public UserAccount() {
        // for Jackson (loading users.json)
    }

    public UserAccount(String username, String passwordHash, String role, String displayName,
                       String flatNumber, UserStatus status) {
        this.id = UUID.randomUUID().toString();
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.displayName = displayName;
        this.flatNumber = flatNumber;
        this.status = status;
        this.requestedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getFlatNumber() { return flatNumber; }
    public void setFlatNumber(String flatNumber) { this.flatNumber = flatNumber; }

    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public String getPendingPhotoPath() { return pendingPhotoPath; }
    public void setPendingPhotoPath(String pendingPhotoPath) { this.pendingPhotoPath = pendingPhotoPath; }

    public String getProfilePhotoPath() { return profilePhotoPath; }
    public void setProfilePhotoPath(String profilePhotoPath) { this.profilePhotoPath = profilePhotoPath; }
}