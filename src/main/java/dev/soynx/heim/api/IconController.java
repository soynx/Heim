package dev.soynx.heim.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import dev.soynx.heim.api.dto.ServiceDto;
import dev.soynx.heim.error.NotFoundException;
import dev.soynx.heim.icon.IconFiles;
import dev.soynx.heim.icon.IconFormat;
import dev.soynx.heim.icon.IconLibrary;
import dev.soynx.heim.icon.IconLibrary.LibraryIcon;
import dev.soynx.heim.icon.IconService;

/** Cached icon files, icon upload/refresh and the icon library search. */
@RestController
public class IconController {

    private final IconService icons;
    private final IconFiles files;
    private final IconLibrary library;

    public IconController(IconService icons, IconFiles files, IconLibrary library) {
        this.icons = icons;
        this.files = files;
        this.library = library;
    }

    /** Icon names are unique per fetch, so they can be cached for a long time. */
    @GetMapping("/icons/{file}")
    public ResponseEntity<Resource> icon(@PathVariable String file) {
        Path path = files.find(file).orElseThrow(() -> new NotFoundException("Icon not found: " + file));
        IconFormat format = IconFormat.fromFileName(file).orElse(null);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(format != null ? MediaType.parseMediaType(format.mediaType()) : MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff");
        if (format == IconFormat.SVG) {
            // Opened directly, an SVG is a document; sandboxing keeps any script that slipped through inert.
            response.header("Content-Security-Policy", "sandbox");
        }
        return response.body(new FileSystemResource(path));
    }

    @PostMapping(path = "/api/services/{id}/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ServiceDto> upload(@PathVariable String id, @RequestPart("file") MultipartFile file,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        return ETags.ok(icons.upload(id, file, ETags.expectedRevision(ifMatch)));
    }

    @PostMapping("/api/services/{id}/icon/refresh")
    public ResponseEntity<ServiceDto> refresh(@PathVariable String id) {
        return ETags.ok(icons.refresh(id));
    }

    @GetMapping("/api/icons/library")
    public List<LibraryIcon> searchLibrary(@RequestParam(name = "q", defaultValue = "") String query) {
        return library.search(query);
    }
}
