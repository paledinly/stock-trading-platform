package com.sunmo.stockplatform.intraday;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Local acceleration only. The committed input ledger remains authoritative; one ingestion process per DB. */
@Component
public class IntradayCheckpointStore {
    public record Snapshot(int format, LocalDate day, Instant savedAt, long cursor, String anchor,
                           IntradayEngine.State state) {}
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final Path file;
    private final Duration interval;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "intraday-checkpoint"); t.setDaemon(true); return t;
    });
    private Future<?> pending;
    private Instant lastAttempt;
    private volatile String status = "NOT_SAVED";
    public IntradayCheckpointStore(ObjectMapper mapper,
            @Value("${intraday.checkpoint.enabled:true}") boolean enabled,
            @Value("${intraday.checkpoint.directory:.intraday-checkpoints}") String directory,
            @Value("${intraday.checkpoint.interval:5m}") Duration interval) {
        this.mapper = mapper; this.enabled = enabled; this.file = Path.of(directory).resolve("latest.json.gz");
        if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("Checkpoint interval must be positive");
        this.interval = interval;
    }
    public synchronized boolean due(Instant now) {
        return enabled && (pending == null || pending.isDone())
                && (lastAttempt == null || !now.isBefore(lastAttempt.plus(interval)));
    }
    public String status() { return enabled ? status : "DISABLED"; }
    public Optional<Snapshot> load(LocalDate day, Instant now, IntradayInputRepository inputs) {
        if (!enabled || !Files.isRegularFile(file)) return Optional.empty();
        try (var in = new GZIPInputStream(Files.newInputStream(file))) {
            Snapshot saved = mapper.readValue(in, Snapshot.class);
            if (saved.format() != 1 || !day.equals(saved.day()) || saved.savedAt().isAfter(now)
                    || saved.cursor() <= 0 || saved.state() == null
                    || !inputs.findById(saved.cursor()).map(row -> fingerprint(row.getPayload()).equals(saved.anchor())).orElse(false)) {
                status = "REJECTED_FALLBACK_TO_LEDGER"; return Optional.empty();
            }
            status = "LOADED"; return Optional.of(saved);
        } catch (Exception failure) {
            status = "READ_FAILED_FALLBACK_TO_LEDGER"; return Optional.empty();
        }
    }
    public synchronized void save(LocalDate day, Instant now, IntradayInputEntity anchor, IntradayEngine engine) {
        if (!due(now)) return;
        lastAttempt = now;
        try {
            Snapshot snapshot = new Snapshot(1, day, now, anchor.getId(), fingerprint(anchor.getPayload()), engine.checkpoint(mapper));
            pending = worker.submit(() -> write(snapshot));
        } catch (RuntimeException failure) { status = "WRITE_FAILED"; }
    }
    void write(Snapshot snapshot) {
        Path temp = file.resolveSibling("latest.json.gz.tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (var out = new GZIPOutputStream(Files.newOutputStream(temp))) { mapper.writeValue(out, snapshot); }
            // Do not replace a valid checkpoint if the filesystem cannot provide an atomic replacement.
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            status = "SAVED";
        } catch (Exception failure) { status = "WRITE_FAILED"; }
        finally { try { Files.deleteIfExists(temp); } catch (Exception ignored) { /* retried on next save */ } }
    }
    static String fingerprint(String payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @PreDestroy public void close() { worker.shutdown(); }
}
