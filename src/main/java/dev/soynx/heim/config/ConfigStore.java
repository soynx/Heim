package dev.soynx.heim.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.soynx.heim.config.migration.MigrationChain;
import dev.soynx.heim.config.model.AuthSettings;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.error.RevisionConflictException;

/**
 * Owner of {@code config.json}. Readers get deep copies; writers go through {@link #update}, which
 * mutates a copy, repairs invariants, writes atomically (keeping {@code config.json.bak}) and then
 * publishes a {@link ConfigChangedEvent}.
 */
@Component
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private final DataPaths paths;
    private final MigrationChain migrations;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Never mutated after being assigned; guarded by {@link #lock}. */
    private HeimConfig current;
    /** The auth settings of {@link #current}; published separately so per-request checks need no deep copy. */
    private volatile AuthSettings auth;

    public ConfigStore(DataPaths paths, MigrationChain migrations, ApplicationEventPublisher events) {
        this.paths = paths;
        this.migrations = migrations;
        this.events = events;
        load();
    }

    /** Deep copy of the current config; mutating it has no effect on the store. */
    public HeimConfig read() {
        lock.readLock().lock();
        try {
            return copy(current);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Read-only view of the current auth settings, cheap enough to call on every request. */
    public AuthSettings auth() {
        AuthSettings snapshot = auth;
        AuthSettings view = new AuthSettings();
        view.setRequireLoginToView(snapshot.isRequireLoginToView());
        view.setRequireLoginToEdit(snapshot.isRequireLoginToEdit());
        view.setUsername(snapshot.getUsername());
        view.setPasswordHash(snapshot.getPasswordHash());
        return view;
    }

    public long revision() {
        lock.readLock().lock();
        try {
            return current.getRevision();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Applies {@code mutator} to a copy of the config and persists the result.
     *
     * @param expectedRevision revision the client based its change on, or null to skip the check
     * @param mutator          mutates the given working copy and returns a value for the caller;
     *                         throwing aborts the update without saving anything
     * @throws RevisionConflictException if {@code expectedRevision} is stale
     */
    public <T> Versioned<T> update(Long expectedRevision, Function<HeimConfig, T> mutator) {
        HeimConfig saved;
        T value;
        lock.writeLock().lock();
        try {
            long revision = current.getRevision();
            if (expectedRevision != null && expectedRevision != revision) {
                throw new RevisionConflictException(expectedRevision, revision);
            }
            HeimConfig working = copy(current);
            value = mutator.apply(working);
            ConfigRepair.repair(working);
            working.setRevision(revision + 1);
            write(working);
            current = copy(working);
            auth = current.getSettings().getAuth();
            saved = working;
        } finally {
            lock.writeLock().unlock();
        }
        events.publishEvent(new ConfigChangedEvent(saved.getRevision(), saved));
        return new Versioned<>(value, saved.getRevision());
    }

    /** Replaces the whole config (e.g. on backup import); {@code replacement} should come from {@link #parse}. */
    public Versioned<Void> replace(HeimConfig replacement) {
        HeimConfig incoming = copy(replacement);
        return update(null, working -> {
            working.setSchemaVersion(incoming.getSchemaVersion());
            working.setSettings(incoming.getSettings());
            working.setTabs(incoming.getTabs());
            working.setServices(incoming.getServices());
            working.getExtra().clear();
            working.getExtra().putAll(incoming.getExtra());
            return null;
        });
    }

    /** Parses, migrates and repairs config JSON without touching the store. */
    public HeimConfig parse(byte[] json) throws IOException {
        return decode(json).config();
    }

    /** Serializes a config in the on-disk format. */
    public byte[] toJson(HeimConfig config) {
        try {
            return mapper.writeValueAsBytes(config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void load() {
        Path file = paths.configFile();
        Decoded decoded;
        if (!Files.exists(file)) {
            log.info("No config found, creating default config at {}", file);
            decoded = new Decoded(DefaultConfig.create(), true);
        } else {
            decoded = readFile(file);
            if (decoded == null) {
                Path backup = backupFile();
                decoded = Files.exists(backup) ? readFile(backup) : null;
                if (decoded == null) {
                    throw new IllegalStateException("Config " + file + " is unreadable and no usable backup exists");
                }
                quarantine(file);
                log.warn("Recovered config from {}", backup);
                decoded = new Decoded(decoded.config(), true);
            }
        }
        current = decoded.config();
        auth = current.getSettings().getAuth();
        if (decoded.changed()) {
            current.setRevision(current.getRevision() + 1);
            write(current);
        }
        log.info("Loaded config revision {} ({} tabs, {} services)",
                current.getRevision(), current.getTabs().size(), current.getServices().size());
    }

    private Decoded readFile(Path file) {
        try {
            return decode(Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            log.error("Failed to read config {}: {}", file, e.getMessage());
            return null;
        }
    }

    private Decoded decode(byte[] json) throws IOException {
        JsonNode tree = mapper.readTree(json);
        if (!(tree instanceof ObjectNode root)) {
            throw new IOException("Config root must be a JSON object");
        }
        boolean migrated = migrations.migrate(root);
        HeimConfig config = mapper.treeToValue(root, HeimConfig.class);
        List<String> fixes = ConfigRepair.repair(config);
        fixes.forEach(fix -> log.warn("Config repaired: {}", fix));
        return new Decoded(config, migrated || !fixes.isEmpty());
    }

    private void write(HeimConfig config) {
        Path file = paths.configFile();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(toJson(config)));
                channel.force(true);
            }
            if (Files.exists(file)) {
                Files.copy(file, backupFile(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save config to " + file, e);
        }
    }

    /** Moves an unreadable config aside so the next save does not overwrite the good backup with it. */
    private void quarantine(Path file) {
        Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, target);
            log.warn("Moved unreadable config to {}", target);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot move unreadable config " + file, e);
        }
    }

    private Path backupFile() {
        Path file = paths.configFile();
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    private HeimConfig copy(HeimConfig config) {
        try {
            return mapper.readValue(mapper.writeValueAsBytes(config), HeimConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Decoded(HeimConfig config, boolean changed) {
    }
}
