package com.acousticcctv.backend.controller;

import com.acousticcctv.backend.model.UserAccount;
import com.acousticcctv.backend.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final String PENDING_PHOTO_DIR = "pending-enrollment-photos";
    private static final String PROFILE_PHOTO_DIR = "profile-photos";

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        UserService.LoginResult result = userService.login(request.username(), request.password());
        if (!result.success()) {
            return ResponseEntity.status(401).body(Map.of("message", result.message()));
        }
        UserAccount account = result.account();
        return ResponseEntity.ok(Map.of(
                "id", account.getId(),
                "username", account.getUsername(),
                "role", account.getRole(),
                "displayName", account.getDisplayName(),
                "flatNumber", account.getFlatNumber() == null ? "" : account.getFlatNumber(),
                "profilePhotoPath", account.getProfilePhotoPath() == null ? "" : account.getProfilePhotoPath()
        ));
    }

    @PostMapping("/request-account")
    public ResponseEntity<?> requestAccount(
            @RequestParam("username") String username,
            @RequestParam("password") String password,
            @RequestParam("displayName") String displayName,
            @RequestParam(value = "flatNumber", required = false) String flatNumber,
            @RequestParam("photo") MultipartFile photo) throws IOException {

        Files.createDirectories(Paths.get(PENDING_PHOTO_DIR));
        String filename = username + "_" + System.currentTimeMillis() + ".jpg";
        Path destination = Paths.get(PENDING_PHOTO_DIR, filename);
        photo.transferTo(destination);

        try {
            userService.requestAccount(username, password, displayName, flatNumber, destination.toFile());
            return ResponseEntity.ok(Map.of("message", "Request submitted - waiting for admin approval."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/pending")
    public List<Map<String, Object>> pending() {
        return userService.pendingRequests().stream()
                .map(a -> Map.<String, Object>of(
                        "id", a.getId(),
                        "username", a.getUsername(),
                        "displayName", a.getDisplayName(),
                        "flatNumber", a.getFlatNumber() == null ? "" : a.getFlatNumber(),
                        "requestedAt", a.getRequestedAt().toString()
                ))
                .collect(Collectors.toList());
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable String id) {
        try {
            userService.approve(id);
            return ResponseEntity.ok(Map.of("message", "Approved."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable String id) {
        try {
            userService.reject(id);
            return ResponseEntity.ok(Map.of("message", "Rejected."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    @PutMapping("/{id}/password")
    public ResponseEntity<?> changePassword(@PathVariable String id, @RequestBody ChangePasswordRequest request) {
        try {
            UserService.PasswordChangeResult result =
                    userService.changePassword(id, request.currentPassword(), request.newPassword());
            if (!result.success()) {
                return ResponseEntity.badRequest().body(Map.of("message", result.message()));
            }
            return ResponseEntity.ok(Map.of("message", result.message()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}/profile")
    public ResponseEntity<?> updateProfile(
            @PathVariable String id,
            @RequestParam(value = "displayName", required = false) String displayName,
            @RequestParam(value = "photo", required = false) MultipartFile photo) throws IOException {

        String photoRelativePath = null;
        if (photo != null && !photo.isEmpty()) {
            Files.createDirectories(Paths.get(PROFILE_PHOTO_DIR));
            String filename = id + "_" + System.currentTimeMillis() + ".jpg";
            Path destination = Paths.get(PROFILE_PHOTO_DIR, filename);
            photo.transferTo(destination);
            photoRelativePath = destination.toString();
        }

        try {
            UserAccount updated = userService.updateProfile(id, displayName, photoRelativePath);
            return ResponseEntity.ok(Map.of(
                    "displayName", updated.getDisplayName(),
                    "profilePhotoPath", updated.getProfilePhotoPath() == null ? "" : updated.getProfilePhotoPath()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }
}