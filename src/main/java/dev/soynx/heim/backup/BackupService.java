package dev.soynx.heim.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.DataPaths;
import dev.soynx.heim.config.model.AuthSettings;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.error.BadRequestException;
import dev.soynx.heim.icon.IconFetcher;
import dev.soynx.heim.icon.IconFormat;
import dev.soynx.heim.service.ImageType;
import dev.soynx.heim.service.SettingsManager;

/**
 * Backup archives: {@code config.json} (credentials stripped), {@code icons/*} and {@code uploads/*}.
 * Import validates the whole archive, including every file's content, into a staging area before
 * swapping anything in, and keeps the current auth settings.
 */
@Component
public class BackupService {

    public static final long MAX_ARCHIVE_BYTES = 200L * 1024 * 1024;
    static final String CONFIG_ENTRY = "config.json";
    static final String ICONS = "icons";
    static final String UPLOADS = "uploads";

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final long MAX_CONFIG_BYTES = 20L * 1024 * 1024;
    /** Total uncompressed size; guards against zip bombs. */
    private static final long MAX_EXTRACTED_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;

    private final ConfigStore store;
    private final DataPaths paths;

    public BackupService(ConfigStore store, DataPaths paths) {
        this.store = store;
        this.paths = paths;
    }

    /** Writes the backup zip to {@code out}. */
    public void export(OutputStream out) throws IOException {
        HeimConfig config = store.read();
        config.getSettings().setAuth(new AuthSettings());
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(CONFIG_ENTRY));
            zip.write(store.toJson(config));
            zip.closeEntry();
            addDirectory(zip, ICONS, paths.iconsDir());
            addDirectory(zip, UPLOADS, paths.uploadsDir());
        }
    }

    /**
     * Imports a backup zip and returns the new config revision.
     *
     * @throws BadRequestException if the archive or its config is invalid; nothing is changed then
     */
    public synchronized long importArchive(InputStream archive) {
        Path dataDir = paths.dataDir();
        String id = UUID.randomUUID().toString();
        Path staging = dataDir.resolve(".import-" + id);
        Path previous = dataDir.resolve(".import-old-" + id);
        try {
            Files.createDirectories(staging.resolve(ICONS));
            Files.createDirectories(staging.resolve(UPLOADS));
            byte[] configJson = extract(archive, staging);
            HeimConfig config = parseConfig(configJson);
            config.getSettings().setAuth(store.read().getSettings().getAuth());
            return swapIn(config, staging, previous);
        } catch (IOException e) {
            throw new UncheckedIOException("Backup import failed", e);
        } finally {
            deleteQuietly(staging);
            deleteQuietly(previous);
        }
    }

    /** Extracts icons/uploads into {@code staging} and returns the config bytes. */
    private byte[] extract(InputStream archive, Path staging) throws IOException {
        byte[] configJson = null;
        Set<String> seen = new HashSet<>();
        long extracted = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(archive)) {
            ZipEntry entry;
            while ((entry = nextEntry(zip)) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new BadRequestException("Backup contains too many entries");
                }
                String name = entry.getName();
                if (entry.isDirectory()) {
                    if (!name.equals(ICONS + "/") && !name.equals(UPLOADS + "/")) {
                        throw new BadRequestException("Unexpected entry in backup: " + name);
                    }
                    continue;
                }
                if (!seen.add(name)) {
                    throw new BadRequestException("Duplicate entry in backup: " + name);
                }
                if (name.equals(CONFIG_ENTRY)) {
                    configJson = readLimited(zip, CONFIG_ENTRY, MAX_CONFIG_BYTES);
                    extracted += configJson.length;
                    continue;
                }
                Path target = stagingTarget(staging, name);
                byte[] content = validatedContent(name, readLimited(zip, name, maxFileBytes(name)));
                extracted += content.length;
                if (extracted > MAX_EXTRACTED_BYTES) {
                    throw new BadRequestException("Backup is too large when extracted");
                }
                Files.write(target, content);
            }
        }
        if (configJson == null) {
            throw new BadRequestException("Backup does not contain " + CONFIG_ENTRY);
        }
        return configJson;
    }

    /** Only {@code icons/<file>} and {@code uploads/<file>} with safe bare file names are accepted. */
    private static Path stagingTarget(Path staging, String name) {
        int slash = name.indexOf('/');
        String dir = slash > 0 ? name.substring(0, slash) : null;
        if (dir == null || !(dir.equals(ICONS) || dir.equals(UPLOADS))) {
            throw new BadRequestException("Unexpected entry in backup: " + name);
        }
        Path target = DataPaths.resolveSafe(staging.resolve(dir), name.substring(slash + 1));
        if (target == null) {
            throw new BadRequestException("Unsafe file name in backup: " + name);
        }
        return target;
    }

    private static long maxFileBytes(String name) {
        return name.startsWith(ICONS + "/") ? IconFetcher.MAX_ICON_BYTES : SettingsManager.MAX_BACKGROUND_BYTES;
    }

    /**
     * Files are served from Heim's origin, so an archive must not smuggle in HTML or scripted SVG: icons
     * get the same validation and sanitizing as fetched ones, uploads must be raster images, and the
     * extension must match the content.
     */
    private static byte[] validatedContent(String name, byte[] content) {
        String fileName = name.substring(name.indexOf('/') + 1);
        if (name.startsWith(ICONS + "/")) {
            return IconFetcher.validate(content)
                    .filter(icon -> IconFormat.fromFileName(fileName).equals(Optional.of(icon.format())))
                    .orElseThrow(() -> new BadRequestException("Not a valid icon in backup: " + name))
                    .content();
        }
        ImageType.detect(content)
                .filter(type -> fileName.toLowerCase(Locale.ROOT).endsWith("." + type.extension()))
                .orElseThrow(() -> new BadRequestException("Not a valid image in backup: " + name));
        return content;
    }

    private HeimConfig parseConfig(byte[] json) {
        try {
            return store.parse(json);
        } catch (IOException | RuntimeException e) {
            throw new BadRequestException("Backup config.json is invalid: " + e.getMessage());
        }
    }

    /** Moves the staged directories into place and saves the config, rolling back on failure. */
    private long swapIn(HeimConfig config, Path staging, Path previous) throws IOException {
        Files.createDirectories(previous);
        Deque<Path[]> done = new ArrayDeque<>();
        try {
            for (String dir : List.of(ICONS, UPLOADS)) {
                Path live = paths.dataDir().resolve(dir);
                move(live, previous.resolve(dir));
                done.push(new Path[] {live, previous.resolve(dir)});
                move(staging.resolve(dir), live);
                done.push(new Path[] {staging.resolve(dir), live});
            }
            long revision = store.replace(config).revision();
            log.info("Imported backup as config revision {}", revision);
            return revision;
        } catch (IOException | RuntimeException e) {
            while (!done.isEmpty()) {
                Path[] step = done.pop();
                try {
                    move(step[1], step[0]);
                } catch (IOException rollback) {
                    log.error("Rollback of backup import failed for {}: {}", step[1], rollback.getMessage());
                }
            }
            throw e;
        }
    }

    private static void addDirectory(ZipOutputStream zip, String prefix, Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> DataPaths.resolveSafe(dir, p.getFileName().toString()) != null)
                    .sorted()
                    .toList();
        }
        for (Path file : files) {
            zip.putNextEntry(new ZipEntry(prefix + "/" + file.getFileName()));
            Files.copy(file, zip);
            zip.closeEntry();
        }
    }

    private static ZipEntry nextEntry(ZipInputStream zip) {
        try {
            return zip.getNextEntry();
        } catch (IOException | IllegalArgumentException e) {
            throw new BadRequestException("Backup is not a valid zip archive");
        }
    }

    private static byte[] readLimited(InputStream in, String name, long limit) {
        try {
            byte[] bytes = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE));
            if (bytes.length > limit) {
                throw new BadRequestException(name + " is too large");
            }
            return bytes;
        } catch (IOException e) {
            throw new BadRequestException("Backup is not a valid zip archive");
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static void deleteQuietly(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean up {}: {}", root, e.getMessage());
        }
    }
}
