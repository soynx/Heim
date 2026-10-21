package dev.soynx.heim.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import dev.soynx.heim.api.dto.SettingsDto;
import dev.soynx.heim.api.dto.SettingsRequest;
import dev.soynx.heim.config.Tagged;
import dev.soynx.heim.security.AuthSettingsService;
import dev.soynx.heim.service.SettingsManager;
import jakarta.validation.Valid;

/** General settings. Credentials and login toggles live under {@code PUT /api/settings/auth}. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsManager settings;
    private final AuthSettingsService auth;

    public SettingsController(SettingsManager settings, AuthSettingsService auth) {
        this.settings = settings;
        this.auth = auth;
    }

    @GetMapping
    public ResponseEntity<SettingsDto> get() {
        return ok(settings.get());
    }

    @PutMapping
    public ResponseEntity<SettingsDto> update(@Valid @RequestBody SettingsRequest request,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        return ok(settings.update(request, ETags.expected(ifMatch)));
    }

    @PostMapping(path = "/background", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SettingsDto> uploadBackground(@RequestPart("file") MultipartFile file,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        return ok(settings.uploadBackground(file, ETags.expected(ifMatch)));
    }

    /** The tag does not cover the auth part, so dropping the username keeps it valid. */
    private ResponseEntity<SettingsDto> ok(Tagged<SettingsDto> tagged) {
        boolean admin = auth.isAdmin(SecurityContextHolder.getContext().getAuthentication());
        return ETags.ok(new Tagged<>(tagged.value().forViewer(admin), tagged.etag()));
    }
}
