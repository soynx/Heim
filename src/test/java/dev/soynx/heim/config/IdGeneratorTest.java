package dev.soynx.heim.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class IdGeneratorTest {

    @Test
    void idsHavePrefixAndEightLowercaseAlphanumerics() {
        assertThat(IdGenerator.serviceId()).matches("svc_[0-9a-z]{8}");
        assertThat(IdGenerator.linkId()).matches("lnk_[0-9a-z]{8}");
        assertThat(IdGenerator.tabId()).matches("tab_[0-9a-z]{8}");
        assertThat(IdGenerator.groupId()).matches("grp_[0-9a-z]{8}");
        assertThat(IdGenerator.newId("background_")).matches("background_[0-9a-z]{8}");
    }

    @Test
    void idsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            ids.add(IdGenerator.serviceId());
        }
        assertThat(ids).hasSize(20_000);
    }

    @Test
    void idsAreSafeUploadFileNames() {
        String name = IdGenerator.newId("background_") + ".png";
        assertThat(DataPaths.resolveSafe(java.nio.file.Path.of("/data/uploads"), name)).isNotNull();
    }
}
