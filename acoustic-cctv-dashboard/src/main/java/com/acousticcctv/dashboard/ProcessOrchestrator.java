package com.acousticcctv.dashboard;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

public class ProcessOrchestrator {

    private final ManagedProcess anpr;
    private final ManagedProcess face;
    private final ManagedProcess backend;
    private final ManagedProcess infer;

    public ProcessOrchestrator(ServiceConfig config) {
        anpr = new ManagedProcess(
                "anpr-service",
                new String[]{config.get("anpr.python"), config.get("anpr.script")},
                new File(config.get("anpr.dir")),
                config.get("anpr.health"));

        face = new ManagedProcess(
                "face-service",
                new String[]{config.get("face.python"), config.get("face.script")},
                new File(config.get("face.dir")),
                config.get("face.health"));

        backend = new ManagedProcess(
                "backend",
                new String[]{"java", "-jar", findBackendJar(config.get("backend.dir"))},
                new File(config.get("backend.dir")),
                config.get("backend.health"));

        infer = new ManagedProcess(
                "live_infer",
                new String[]{config.get("infer.python"), config.get("infer.script")},
                new File(config.get("infer.dir")),
                null);
    }

    private static String findBackendJar(String backendDir) {
        File targetDir = new File(backendDir, "target");
        File[] candidates = targetDir.listFiles((dir, name) ->
                name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"));
        if (candidates == null || candidates.length == 0) {
            throw new IllegalStateException("No backend jar found in " + targetDir.getAbsolutePath() +
                    " - run 'mvnw.cmd clean package' in the backend project first.");
        }
        File newest = candidates[0];
        for (File f : candidates) {
            if (f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        return newest.getAbsolutePath();
    }

    public boolean startBackendOnly(Consumer<String> onLogLine) {
        return launchAndWait(backend, onLogLine, 60);
    }

    public boolean startAll(Consumer<String> onLogLine) {
        onLogLine.accept("=== Starting anpr-service and face-service ===");
        if (!launchAndWait(anpr, onLogLine, 60) || !launchAndWait(face, onLogLine, 60)) {
            return false;
        }

        if (backend.isRunning()) {
            onLogLine.accept("backend already running.");
        } else {
            onLogLine.accept("=== Starting backend ===");
            if (!launchAndWait(backend, onLogLine, 60)) {
                return false;
            }
        }

        onLogLine.accept("=== Starting live_infer.py ===");
        try {
            infer.start(onLogLine);
        } catch (IOException e) {
            onLogLine.accept("ERROR: failed to start live_infer.py: " + e.getMessage());
            return false;
        }

        onLogLine.accept("=== All processes running ===");
        return true;
    }

    private boolean launchAndWait(ManagedProcess proc, Consumer<String> onLogLine, int timeoutSeconds) {
        try {
            proc.start(onLogLine);
        } catch (IOException e) {
            onLogLine.accept("ERROR: failed to start " + proc.getName() + ": " + e.getMessage());
            return false;
        }
        onLogLine.accept("Waiting for " + proc.getName() + " to become healthy...");
        boolean healthy = proc.waitUntilHealthy(timeoutSeconds);
        onLogLine.accept(healthy
                ? proc.getName() + " is up."
                : "ERROR: " + proc.getName() + " did not become healthy within " + timeoutSeconds + "s");
        return healthy;
    }

    /** Stops just anpr-service, face-service, and live_infer.py - NOT the
     * backend, so previously recorded events/recordings stay browsable
     * in the dashboard even after "stopping the system". Used by the
     * Stop System button. The backend only stops when the whole app
     * closes (see DashboardApp's shutdown hook, which still calls
     * stopAll()). */
    public void stopMonitoring(Consumer<String> onLogLine) {
        onLogLine.accept("=== Stopping live_infer.py ===");
        infer.stop();
        onLogLine.accept("=== Stopping face-service and anpr-service ===");
        face.stop();
        anpr.stop();
        onLogLine.accept("=== Monitoring stopped (backend still running - recordings still browsable) ===");
    }

    /** Stops everything, including the backend - used only when the
     * whole app is closing. */
    public void stopAll(Consumer<String> onLogLine) {
        onLogLine.accept("=== Stopping live_infer.py ===");
        infer.stop();
        onLogLine.accept("=== Stopping backend ===");
        backend.stop();
        onLogLine.accept("=== Stopping face-service and anpr-service ===");
        face.stop();
        anpr.stop();
        onLogLine.accept("=== All processes stopped ===");
    }

    public boolean isRunning() {
        return anpr.isRunning() || face.isRunning() || backend.isRunning() || infer.isRunning();
    }

    public boolean isFullyRunning() {
        return anpr.isRunning() || face.isRunning() || infer.isRunning();
    }
}