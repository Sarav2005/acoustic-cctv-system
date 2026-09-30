package com.acousticcctv.backend.repository;

import com.acousticcctv.backend.model.UserAccount;
import com.acousticcctv.backend.model.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

/**
 * User accounts persist to users.json - deliberately NOT in-memory like
 * Event, since losing every account (admin included) on every backend
 * restart during dev/testing would be genuinely disruptive. Bootstraps
 * one default admin account on first run with a random password printed
 * once to the console - same idea as Spring Boot's own default-password
 * behavior.
 */
@Repository
public class UserAccountRepository {

    private static final String STORAGE_FILE = "users.json";
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Map<String, UserAccount> byId = new LinkedHashMap<>();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserAccountRepository() {
        load();
        if (byId.isEmpty()) {
            bootstrapDefaultAdmin();
        }
    }

    private void bootstrapDefaultAdmin() {
        String randomPassword = generateRandomPassword();
        UserAccount admin = new UserAccount("admin", encoder.encode(randomPassword), "ADMIN",
                "Administrator", null, UserStatus.APPROVED);
        byId.put(admin.getId(), admin);
        persist();
        System.out.println("=================================================================");
        System.out.println("  No users.json found - created a default admin account:");
        System.out.println("    username: admin");
        System.out.println("    password: " + randomPassword);
        System.out.println("  Save this password now - it will not be shown again.");
        System.out.println("=================================================================");
    }

    private String generateRandomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private synchronized void load() {
        File file = new File(STORAGE_FILE);
        if (!file.exists()) {
            return;
        }
        try {
            List<UserAccount> loaded = mapper.readValue(file,
                    mapper.getTypeFactory().constructCollectionType(List.class, UserAccount.class));
            for (UserAccount u : loaded) {
                byId.put(u.getId(), u);
            }
        } catch (IOException e) {
            System.err.println("WARNING: failed to load " + STORAGE_FILE + ": " + e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(STORAGE_FILE), byId.values());
        } catch (IOException e) {
            System.err.println("WARNING: failed to save " + STORAGE_FILE + ": " + e.getMessage());
        }
    }

    public synchronized UserAccount save(UserAccount account) {
        byId.put(account.getId(), account);
        persist();
        return account;
    }

    public synchronized Optional<UserAccount> findByUsername(String username) {
        return byId.values().stream().filter(u -> u.getUsername().equalsIgnoreCase(username)).findFirst();
    }

    public synchronized Optional<UserAccount> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public synchronized List<UserAccount> findByStatus(UserStatus status) {
        return byId.values().stream().filter(u -> u.getStatus() == status).collect(Collectors.toList());
    }

    public boolean matchesPassword(UserAccount account, String rawPassword) {
        return encoder.matches(rawPassword, account.getPasswordHash());
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }
}