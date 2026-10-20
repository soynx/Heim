package dev.soynx.heim.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import dev.soynx.heim.backup.BackupService;
import dev.soynx.heim.error.BadRequestException;
import jakarta.servlet.http.HttpServletResponse;

/** Backup export/import as a zip of config (without credentials), icons and uploads. */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        String fileName = "heim-backup-" + LocalDate.now() + ".zip";
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        backups.export(response.getOutputStream());
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> importBackup(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file is required");
        }
        if (file.getSize() > BackupService.MAX_ARCHIVE_BYTES) {
            throw new BadRequestException("Backup must be at most 200 MB");
        }
        long revision;
        try (InputStream in = file.getInputStream()) {
            revision = backups.importArchive(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ResponseEntity.ok().eTag(ETags.of(revision)).body(new ImportResult(revision));
    }

    public record ImportResult(long revision) {
    }
}
