package dev.soynx.heim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import dev.soynx.heim.api.dto.SettingsDto;
import dev.soynx.heim.api.dto.SettingsRequest;
import dev.soynx.heim.api.dto.SettingsRequest.BackgroundRequest;
import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.TestStores;
import dev.soynx.heim.config.model.BackgroundSource;
import dev.soynx.heim.error.BadRequestException;
import dev.soynx.heim.error.RevisionConflictException;

class SettingsManagerTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};

    @TempDir
    Path dir;

    private ConfigStore store;
    private SettingsManager settings;

    @BeforeEach
    void setUp() {
        store = TestStores.open(dir);
        settings = new SettingsManager(store, new UploadStorage(TestStores.paths(dir)));
    }

    private long uploadCount() throws IOException {
        try (Stream<Path> files = Files.list(dir.resolve("uploads"))) {
            return files.count();
        }
    }

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "bg.png", "image/png", content);
    }

    @Test
    void partialUpdateLeavesNullFieldsUnchanged() {
        settings.update(new SettingsRequest(" My Home ", "dark", null, "body{}", null), null);
        SettingsDto dto = settings.update(new SettingsRequest(null, null, null, null, null), null).value();

        assertThat(dto.title()).isEqualTo("My Home");
        assertThat(dto.theme()).isEqualTo("dark");
        assertThat(dto.customCss()).isEqualTo("body{}");
        assertThat(settings.customCss()).isEqualTo("body{}");
    }

    @Test
    void blankTitleRejected() {
        assertThatThrownBy(() -> settings.update(new SettingsRequest(" ", null, null, null, null), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void uploadRejectsNonImages() throws IOException {
        assertThatThrownBy(() -> settings.uploadBackground(file("hello world".getBytes()), null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> settings.uploadBackground(file(new byte[0]), null))
                .isInstanceOf(BadRequestException.class);
        assertThat(uploadCount()).isZero();
    }

    @Test
    void uploadStoresFileAndReplacingDeletesPrevious() throws IOException {
        SettingsDto first = settings.uploadBackground(file(PNG), null).value();
        String firstFile = first.background().getCachedFile();
        assertThat(first.background().getSource()).isEqualTo(BackgroundSource.UPLOAD);
        assertThat(first.background().getValue()).isEqualTo("bg.png");
        assertThat(firstFile).matches("background_[0-9a-z]{8}\\.png");
        assertThat(dir.resolve("uploads").resolve(firstFile)).exists();

        String secondFile = settings.uploadBackground(file(PNG), null).value().background().getCachedFile();
        assertThat(secondFile).isNotEqualTo(firstFile);
        assertThat(dir.resolve("uploads").resolve(firstFile)).doesNotExist();
        assertThat(uploadCount()).isEqualTo(1);

        // Keeping the upload is allowed and keeps the file.
        settings.update(new SettingsRequest(null, null, new BackgroundRequest(BackgroundSource.UPLOAD, null), null,
                null), null);
        assertThat(dir.resolve("uploads").resolve(secondFile)).exists();

        // Switching to none removes the file.
        SettingsDto none = settings.update(new SettingsRequest(null, null,
                new BackgroundRequest(BackgroundSource.NONE, null), null, null), null).value();
        assertThat(none.background().getCachedFile()).isNull();
        assertThat(uploadCount()).isZero();
    }

    @Test
    void uploadWithStaleRevisionLeavesNoFile() throws IOException {
        long stale = store.revision() - 1;
        assertThatThrownBy(() -> settings.uploadBackground(file(PNG), stale))
                .isInstanceOf(RevisionConflictException.class);
        assertThat(uploadCount()).isZero();
    }

    @Test
    void backgroundUrlValidatedAndUploadSourceRequiresExistingUpload() {
        SettingsDto dto = settings.update(new SettingsRequest(null, null,
                new BackgroundRequest(BackgroundSource.URL, "https://example.com/bg.jpg"), null, null), null).value();
        assertThat(dto.background().getValue()).isEqualTo("https://example.com/bg.jpg");

        assertThatThrownBy(() -> settings.update(new SettingsRequest(null, null,
                new BackgroundRequest(BackgroundSource.URL, "javascript:alert(1)"), null, null), null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> settings.update(new SettingsRequest(null, null,
                new BackgroundRequest(BackgroundSource.UPLOAD, null), null, null), null))
                .isInstanceOf(BadRequestException.class);
    }
}
