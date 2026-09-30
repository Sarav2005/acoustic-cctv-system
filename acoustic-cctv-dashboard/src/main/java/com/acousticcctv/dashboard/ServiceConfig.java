package com.acousticcctv.dashboard;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads the machine-specific paths this dashboard needs to launch the
 * other 4 processes, from dashboard.properties.
 */
public class ServiceConfig {

    private final Properties props = new Properties();

    public ServiceConfig() {
        try (InputStream in = ServiceConfig.class.getResourceAsStream("/dashboard.properties")) {
            if (in == null) {
                throw new IllegalStateException("dashboard.properties not found on classpath - " +
                        "make sure it's in src/main/resources");
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load dashboard.properties", e);
        }
    }

    public String get(String key) {
        String value = props.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("Missing config key: " + key + " in dashboard.properties");
        }
        return value;
    }
}
