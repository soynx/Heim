package dev.soynx.heim.icon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.soynx.heim.config.DataPaths;
import dev.soynx.heim.config.IdGenerator;

/** Cached and uploaded icons in the icons directory, named {@code <serviceId>_<random>.<ext>}. */
@Component
public class IconFiles {

    private static final Logger log = LoggerFactory.getLogger(IconFiles.class);

    private final Path dir;

    public IconFiles(DataPaths paths) {
        this.dir = paths.iconsDir();
    }

    /** Writes the icon under a fresh unique name and returns that name. */
    public String save(String ownerId, IconFetcher.IconFile icon) {
        String name = IdGenerator.newId(ownerId + "_") + "." + icon.format().extension();
        Path target = dir.resolve(name);
        Path tmp = dir.resolve("." + name + ".tmp");
        try {
            Files.write(tmp, icon.content());
            Files.move(tmp, target);
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new UncheckedIOException("Failed to store icon " + name, e);
        }
        return name;
    }

    public Optional<Path> find(String fileName) {
        Path path = DataPaths.resolveSafe(dir, fileName);
        return path != null && Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /** Best effort; a leftover file is harmless. */
    public void delete(String fileName) {
        find(fileName).ifPresent(IconFiles::deleteQuietly);
    }

    /**
     * Deletes files that are not in {@code keep}. Files younger than {@code grace} survive, so icons
     * that are written but not yet referenced by a saved config (or restored by an import) are safe.
     *
     * @return how many files were deleted and how many were spared only for being too young
     */
    public Cleanup deleteUnreferenced(Set<String> keep, Duration grace) {
        Instant cutoff = Instant.now().minus(grace);
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            log.warn("Cannot list icons directory {}: {}", dir, e.getMessage());
            return new Cleanup(0, 0);
        }
        int deleted = 0;
        int deferred = 0;
        for (Path file : files) {
            if (keep.contains(file.getFileName().toString())) {
                continue;
            }
            try {
                if (!Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    deferred++;
                } else if (Files.deleteIfExists(file)) {
                    deleted++;
                }
            } catch (IOException e) {
                log.debug("Could not delete orphaned icon {}: {}", file, e.getMessage());
            }
        }
        return new Cleanup(deleted, deferred);
    }

    public record Cleanup(int deleted, int deferred) {
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete icon {}: {}", path, e.getMessage());
        }
    }
}
