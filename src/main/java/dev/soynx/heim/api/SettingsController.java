package dev.soynx.heim.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import dev.soynx.heim.service.SettingsManager;
import jakarta.validation.Valid;

/** General settings. Credentials and login toggles live under {@code PUT /api/settings/auth}. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsManager settings;

    public SettingsController(SettingsManager settings) {
        this.settings = settings;
    }

    @GetMapping
    public ResponseEntity<SettingsDto> get() {
        return ETags.ok(settings.get());
    }

    @PutMapping
    public ResponseEntity<SettingsDto> update(@Valid @RequestBody SettingsRequest request,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        return ETags.ok(settings.update(request, ETags.expectedRevision(ifMatch)));
    }

    @PostMapping(path = "/background", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SettingsDto> uploadBackground(@RequestPart("file") MultipartFile file,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        return ETags.ok(settings.uploadBackground(file, ETags.expectedRevision(ifMatch)));
    }
}
