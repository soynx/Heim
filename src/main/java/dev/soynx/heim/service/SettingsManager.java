package dev.soynx.heim.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import dev.soynx.heim.api.dto.SettingsDto;
import dev.soynx.heim.api.dto.SettingsRequest;
import dev.soynx.heim.api.dto.SettingsRequest.BackgroundRequest;
import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.Versioned;
import dev.soynx.heim.config.model.BackgroundRef;
import dev.soynx.heim.config.model.BackgroundSource;
import dev.soynx.heim.config.model.Settings;
import dev.soynx.heim.error.BadRequestException;

/** General settings, custom CSS and the background image. Auth settings are not handled here. */
@Component
public class SettingsManager {

    public static final long MAX_BACKGROUND_BYTES = 10L * 1024 * 1024;

    private final ConfigStore store;
    private final UploadStorage uploads;

    public SettingsManager(ConfigStore store, UploadStorage uploads) {
        this.store = store;
        this.uploads = uploads;
    }

    public Versioned<SettingsDto> get() {
        var config = store.read();
        return new Versioned<>(SettingsDto.from(config.getSettings()), config.getRevision());
    }

    public String customCss() {
        return store.read().getSettings().getCustomCss();
    }

    public Versioned<SettingsDto> update(SettingsRequest request, Long expectedRevision) {
        Versioned<Change> result = store.update(expectedRevision, config -> {
            Settings settings = config.getSettings();
            String previousFile = settings.getBackground().getCachedFile();
            if (request.title() != null) {
                if (request.title().isBlank()) {
                    throw new BadRequestException("title must not be blank");
                }
                settings.setTitle(request.title().trim());
            }
            if (request.theme() != null) {
                settings.setTheme(request.theme());
            }
            if (request.customCss() != null) {
                settings.setCustomCss(request.customCss());
            }
            if (request.probe() != null) {
                settings.getProbe().setIntervalSeconds(request.probe().getIntervalSeconds());
                settings.getProbe().setTimeoutSeconds(request.probe().getTimeoutSeconds());
            }
            if (request.background() != null) {
                applyBackground(settings.getBackground(), request.background());
            }
            return new Change(SettingsDto.from(settings), previousFile);
        });
        return finish(result);
    }

    /** Stores an uploaded png/jpg/webp/gif and makes it the background. */
    public Versioned<SettingsDto> uploadBackground(MultipartFile file, Long expectedRevision) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file is required");
        }
        if (file.getSize() > MAX_BACKGROUND_BYTES) {
            throw new BadRequestException("Background image must be at most 10 MB");
        }
        byte[] content = readBytes(file);
        ImageType type = ImageType.detect(content)
                .orElseThrow(() -> new BadRequestException("Background must be a PNG, JPEG, WebP or GIF image"));
        String stored = uploads.save("background", type.extension(), content);

        Versioned<Change> result;
        try {
            result = store.update(expectedRevision, config -> {
                BackgroundRef background = config.getSettings().getBackground();
                String previousFile = background.getCachedFile();
                background.setSource(BackgroundSource.UPLOAD);
                background.setValue(file.getOriginalFilename());
                background.setCachedFile(stored);
                return new Change(SettingsDto.from(config.getSettings()), previousFile);
            });
        } catch (RuntimeException e) {
            uploads.delete(stored);
            throw e;
        }
        return finish(result);
    }

    private static void applyBackground(BackgroundRef background, BackgroundRequest request) {
        BackgroundSource source = request.source() != null ? request.source() : BackgroundSource.NONE;
        switch (source) {
            case NONE -> {
                background.setValue(null);
                background.setCachedFile(null);
            }
            case URL -> {
                background.setValue(Urls.requireHttp(request.value(), "background.value"));
                background.setCachedFile(null);
            }
            case UPLOAD -> {
                if (background.getSource() != BackgroundSource.UPLOAD) {
                    throw new BadRequestException("Upload a background via POST /api/settings/background");
                }
            }
        }
        background.setSource(source);
    }

    /** Removes a background file that is no longer referenced once the change is saved. */
    private Versioned<SettingsDto> finish(Versioned<Change> result) {
        String previous = result.value().previousBackgroundFile();
        SettingsDto settings = result.value().settings();
        if (previous != null && !Objects.equals(previous, settings.background().getCachedFile())) {
            uploads.delete(previous);
        }
        return new Versioned<>(settings, result.revision());
    }

    private record Change(SettingsDto settings, String previousBackgroundFile) {
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
