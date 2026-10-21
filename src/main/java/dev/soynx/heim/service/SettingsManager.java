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
import dev.soynx.heim.config.ResourceTags;
import dev.soynx.heim.config.Tagged;
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

    public Tagged<SettingsDto> get() {
        SettingsDto settings = SettingsDto.from(store.read().getSettings());
        return new Tagged<>(settings, tagOf(settings));
    }

    public String customCss() {
        return store.read().getSettings().getCustomCss();
    }

    public Tagged<SettingsDto> update(SettingsRequest request, String expectedTag) {
        Versioned<Change> result = store.update(null, config -> {
            Settings settings = config.getSettings();
            checkTag(expectedTag, settings);
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
                if (request.probe().intervalSeconds() != null) {
                    settings.getProbe().setIntervalSeconds(request.probe().intervalSeconds());
                }
                if (request.probe().timeoutSeconds() != null) {
                    settings.getProbe().setTimeoutSeconds(request.probe().timeoutSeconds());
                }
            }
            if (request.background() != null) {
                applyBackground(settings.getBackground(), request.background());
            }
            return new Change(SettingsDto.from(settings), previousFile);
        });
        return finish(result);
    }

    /** Stores an uploaded png/jpg/webp/gif and makes it the background. */
    public Tagged<SettingsDto> uploadBackground(MultipartFile file, String expectedTag) {
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
            result = store.update(null, config -> {
                checkTag(expectedTag, config.getSettings());
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

    /**
     * Tag of the general settings. The login settings are left out: they are changed through
     * {@code PUT /api/settings/auth}, which must not invalidate an open settings form.
     */
    static String tagOf(SettingsDto settings) {
        return ResourceTags.of(settings, "auth");
    }

    private static void checkTag(String expectedTag, Settings settings) {
        ResourceTags.check(expectedTag, tagOf(SettingsDto.from(settings)), "Settings");
    }

    /** Removes a background file that is no longer referenced once the change is saved. */
    private Tagged<SettingsDto> finish(Versioned<Change> result) {
        String previous = result.value().previousBackgroundFile();
        SettingsDto settings = result.value().settings();
        if (previous != null && !Objects.equals(previous, settings.background().getCachedFile())) {
            uploads.delete(previous);
        }
        return new Tagged<>(settings, tagOf(settings));
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
