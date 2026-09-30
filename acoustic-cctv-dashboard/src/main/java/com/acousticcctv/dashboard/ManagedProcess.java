package com.acousticcctv.dashboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Wraps ONE external process (a Python service or the backend jar):
 * starting it, streaming its stdout/stderr into a log callback, polling
 * a health-check URL until it's ready (or timing out), and stopping it.
 *
 * Deliberately simple - this is a demo control panel, not production
 * infrastructure. Stopping uses Process.destroy(), which on Windows is
 * an abrupt termination (equivalent to closing the terminal window), not
 * a graceful Ctrl+C - the OS reclaims camera/mic/file handles either
 * way, so this is fine here, just not a "clean shutdown" in the Python
 * script's own KeyboardInterrupt sense.
 */
public class ManagedProcess {

    private final String name;
    private final String[] command;
    private final java.io.File workingDir;
    private final String healthUrl;  // null if this process has no health endpoint (live_infer.py)

    private Process process;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public ManagedProcess(String name, String[] command, java.io.File workingDir, String healthUrl) {
        this.name = name;
        this.command = command;
        this.workingDir = workingDir;
        this.healthUrl = healthUrl;
    }

    public String getName() { return name; }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /** Starts the process and streams its combined stdout/stderr, one line
     * at a time, to onLogLine. Returns immediately - doesn't wait for the
     * process to be "ready" (use waitUntilHealthy for that). */
    public void start(Consumer<String> onLogLine) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir);
        builder.redirectErrorStream(true);  // merge stderr into stdout - simplest for one log panel
        process = builder.start();

        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLogLine.accept("[" + name + "] " + line);
                }
            } catch (IOException e) {
                onLogLine.accept("[" + name + "] (log stream ended: " + e.getMessage() + ")");
            }
        }, name + "-log-reader");
        logThread.setDaemon(true);
        logThread.start();
    }

    /** Polls healthUrl every 1s until it returns HTTP 200, or timeoutSeconds
     * elapses. Returns true immediately if this process has no health URL
     * configured (live_infer.py - it's a listening loop, not a server, so
     * it's considered "ready" the moment it's launched). */
    public boolean waitUntilHealthy(int timeoutSeconds) {
        if (healthUrl == null) {
            return true;
        }
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(healthUrl))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return true;
                }
            } catch (Exception ignored) {
                // not up yet - keep polling
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Stops the process if running (graceful destroy, then force after a
     * 5s grace period). See class docstring re: abrupt termination. */
    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
