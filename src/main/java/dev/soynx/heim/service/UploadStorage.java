package dev.soynx.heim.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.soynx.heim.config.DataPaths;
import dev.soynx.heim.config.IdGenerator;

/** User-uploaded files (backgrounds) in the uploads directory. */
@Component
public class UploadStorage {

    private static final Logger log = LoggerFactory.getLogger(UploadStorage.class);

    private final Path dir;

    public UploadStorage(DataPaths paths) {
        this.dir = paths.uploadsDir();
    }

    /** Writes {@code content} under a fresh unique name and returns that name. */
    public String save(String prefix, String extension, byte[] content) {
        String name = IdGenerator.newId(prefix + "_") + "." + extension;
        try {
            Files.write(dir.resolve(name), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store upload " + name, e);
        }
        return name;
    }

    public Optional<Path> find(String fileName) {
        Path path = DataPaths.resolveSafe(dir, fileName);
        return path != null && Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /** Best effort; a leftover file is harmless. */
    public void delete(String fileName) {
        find(fileName).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Could not delete upload {}: {}", path, e.getMessage());
            }
        });
    }
}
