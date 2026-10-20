package dev.soynx.heim.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

/** Locations of everything Heim persists. Directories are created on startup. */
@Component
public class DataPaths {

    private final Path dataDir;
    private final Path iconsDir;
    private final Path uploadsDir;
    private final Path configFile;

    public DataPaths(HeimProperties properties) {
        this.dataDir = properties.dataDir().toAbsolutePath().normalize();
        this.iconsDir = dataDir.resolve("icons");
        this.uploadsDir = dataDir.resolve("uploads");
        this.configFile = dataDir.resolve("config.json");
        try {
            Files.createDirectories(iconsDir);
            Files.createDirectories(uploadsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data directories under " + dataDir, e);
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path iconsDir() {
        return iconsDir;
    }

    public Path uploadsDir() {
        return uploadsDir;
    }

    public Path configFile() {
        return configFile;
    }

    /**
     * Resolves a bare file name inside {@code dir}, rejecting anything that could escape it
     * (separators, "..", hidden files). Returns null for unsafe names.
     */
    public static Path resolveSafe(Path dir, String fileName) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,200}") || fileName.contains("..")) {
            return null;
        }
        Path resolved = dir.resolve(fileName).normalize();
        return resolved.getParent().equals(dir) ? resolved : null;
    }
}
