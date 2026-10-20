package dev.soynx.heim.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import dev.soynx.heim.error.NotFoundException;
import dev.soynx.heim.service.SettingsManager;
import dev.soynx.heim.service.UploadStorage;

/** User assets: custom stylesheet and uploaded files. */
@RestController
public class AssetController {

    private static final MediaType TEXT_CSS = new MediaType("text", "css", StandardCharsets.UTF_8);

    private final SettingsManager settings;
    private final UploadStorage uploads;

    public AssetController(SettingsManager settings, UploadStorage uploads) {
        this.settings = settings;
        this.uploads = uploads;
    }

    @GetMapping("/custom.css")
    public ResponseEntity<String> customCss() {
        return ResponseEntity.ok()
                .contentType(TEXT_CSS)
                .cacheControl(CacheControl.noCache())
                .body(settings.customCss());
    }

    /** Upload names are unique per upload, so they can be cached for a long time. */
    @GetMapping("/uploads/{file}")
    public ResponseEntity<Resource> upload(@PathVariable String file) {
        Path path = uploads.find(file).orElseThrow(() -> new NotFoundException("File not found: " + file));
        MediaType type = MediaTypeFactory.getMediaType(path.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(path));
    }
}
