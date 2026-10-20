package dev.soynx.heim.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DataPathsTest {

    @TempDir
    Path dir;

    @Test
    void createsSubdirectories() {
        DataPaths paths = TestStores.paths(dir);
        assertThat(paths.iconsDir()).isDirectory().isEqualTo(dir.toAbsolutePath().resolve("icons"));
        assertThat(paths.uploadsDir()).isDirectory();
        assertThat(paths.configFile()).isEqualTo(paths.dataDir().resolve("config.json"));
    }

    @Test
    void resolvesPlainFileNames() {
        Path uploads = TestStores.paths(dir).uploadsDir();
        assertThat(DataPaths.resolveSafe(uploads, "background_abc.png")).isEqualTo(uploads.resolve("background_abc.png"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"..", "../config.json", "..config.json", "a/../../b", "a/b", "a\\b", ".hidden",
            "/etc/passwd", "%2e%2e", "a b"})
    void rejectsUnsafeNames(String name) {
        assertThat(DataPaths.resolveSafe(TestStores.paths(dir).uploadsDir(), name)).isNull();
    }
}
