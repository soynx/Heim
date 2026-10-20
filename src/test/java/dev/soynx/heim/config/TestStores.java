package dev.soynx.heim.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.soynx.heim.config.migration.MigrationChain;
import dev.soynx.heim.config.migration.V0ToV1Migration;

/** Builds a real {@link ConfigStore} on a temp directory, without Spring. */
public final class TestStores {

    private TestStores() {
    }

    public static ConfigStore open(Path dataDir) {
        return open(dataDir, new ArrayList<>());
    }

    /** Opens a store whose published {@link ConfigChangedEvent}s are collected into {@code events}. */
    public static ConfigStore open(Path dataDir, List<Object> events) {
        DataPaths paths = new DataPaths(new HeimProperties(dataDir));
        return new ConfigStore(paths, new MigrationChain(List.of(new V0ToV1Migration())), events::add);
    }

    public static DataPaths paths(Path dataDir) {
        return new DataPaths(new HeimProperties(dataDir));
    }

    public static byte[] fixture(String name) {
        try (InputStream in = TestStores.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Copies a fixture to {@code dataDir/config.json}. */
    public static void installFixture(Path dataDir, String name) {
        try {
            Files.createDirectories(dataDir);
            Files.write(dataDir.resolve("config.json"), fixture(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
