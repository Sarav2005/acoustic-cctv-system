package com.acousticcctv.backend.service;

import com.acousticcctv.backend.model.UserAccount;
import com.acousticcctv.backend.model.UserStatus;
import com.acousticcctv.backend.repository.UserAccountRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    private static final String FACE_ENROLL_URL = "http://localhost:5002/enroll";

    private final UserAccountRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();

    public UserService(UserAccountRepository repository) {
        this.repository = repository;
    }

    public record LoginResult(boolean success, String message, UserAccount account) {}

    public LoginResult login(String username, String password) {
        var maybeUser = repository.findByUsername(username);
        if (maybeUser.isEmpty() || !repository.matchesPassword(maybeUser.get(), password)) {
            return new LoginResult(false, "Invalid username or password.", null);
        }
        UserAccount user = maybeUser.get();
        if (user.getStatus() != UserStatus.APPROVED) {
            return new LoginResult(false, "Your account request is still pending admin approval.", null);
        }
        return new LoginResult(true, "OK", user);
    }

    public UserAccount requestAccount(String username, String rawPassword, String displayName,
                                      String flatNumber, File photo) {
        if (repository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("That username is already taken or pending.");
        }
        UserAccount account = new UserAccount(username, repository.hash(rawPassword), "RESIDENT",
                displayName, flatNumber, UserStatus.PENDING);
        account.setPendingPhotoPath(photo.getAbsolutePath());
        return repository.save(account);
    }

    public List<UserAccount> pendingRequests() {
        return repository.findByStatus(UserStatus.PENDING);
    }

    public UserAccount approve(String accountId) {
        UserAccount account = repository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account request."));
        if (account.getPendingPhotoPath() != null) {
            enrollFace(account.getDisplayName(), account.getFlatNumber(), account.getPendingPhotoPath());
        }
        account.setStatus(UserStatus.APPROVED);
        return repository.save(account);
    }

    public UserAccount reject(String accountId) {
        UserAccount account = repository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account request."));
        account.setStatus(UserStatus.REJECTED);
        return repository.save(account);
    }

    public Optional<UserAccount> findById(String id) {
        return repository.findById(id);
    }

    /** newPhotoRelativePath, if given, should already be a path relative
     * to the backend's working directory (same convention as
     * Event.imagePath) - the controller is responsible for saving the
     * uploaded file and computing that relative path before calling
     * this. Null displayName/path arguments leave that field unchanged. */
    public UserAccount updateProfile(String userId, String newDisplayName, String newPhotoRelativePath) {
        UserAccount account = repository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("No such account."));
        if (newDisplayName != null && !newDisplayName.isBlank()) {
            account.setDisplayName(newDisplayName);
        }
        if (newPhotoRelativePath != null) {
            account.setProfilePhotoPath(newPhotoRelativePath);
        }
        return repository.save(account);
    }

    public record PasswordChangeResult(boolean success, String message) {}

    public PasswordChangeResult changePassword(String userId, String currentPassword, String newPassword) {
        UserAccount account = repository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("No such account."));
        if (!repository.matchesPassword(account, currentPassword)) {
            return new PasswordChangeResult(false, "Current password is incorrect.");
        }
        if (newPassword == null || newPassword.length() < 6) {
            return new PasswordChangeResult(false, "New password must be at least 6 characters.");
        }
        account.setPasswordHash(repository.hash(newPassword));
        repository.save(account);
        return new PasswordChangeResult(true, "Password updated.");
    }

    private void enrollFace(String name, String flatNumber, String photoPath) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("image", new FileSystemResource(new File(photoPath)));
            body.add("name", name);
            if (flatNumber != null) {
                body.add("flat_number", flatNumber);
            }
            body.add("status", "resident");

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            restTemplate.postForObject(FACE_ENROLL_URL, requestEntity, String.class);
        } catch (Exception ex) {
            System.err.println("WARNING: face enrollment failed during approval: " + ex.getMessage());
        }
    }
}