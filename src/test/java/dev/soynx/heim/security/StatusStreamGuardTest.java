package dev.soynx.heim.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.soynx.heim.config.ConfigChangedEvent;
import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.TestStores;
import dev.soynx.heim.config.model.AuthSettings;
import dev.soynx.heim.probe.StatusBroadcaster;
import dev.soynx.heim.probe.StatusRegistry;

class StatusStreamGuardTest {

    @TempDir
    Path dataDir;

    private final List<Object> events = new ArrayList<>();
    private final StatusBroadcaster broadcaster = new StatusBroadcaster(new StatusRegistry());
    private ConfigStore store;
    private StatusStreamGuard guard;

    @BeforeEach
    void setUp() {
        store = TestStores.open(dataDir, events);
        guard = new StatusStreamGuard(broadcaster, store);
    }

    private ConfigChangedEvent change(Consumer<AuthSettings> mutation) {
        events.clear();
        store.update(null, c -> {
            mutation.accept(c.getSettings().getAuth());
            return null;
        });
        return (ConfigChangedEvent) events.getLast();
    }

    @Test
    void unrelatedChangesKeepStreamsOpen() {
        broadcaster.subscribe(null);
        events.clear();
        store.update(null, c -> {
            c.getSettings().setTitle("Other");
            return null;
        });
        guard.onConfigChanged((ConfigChangedEvent) events.getLast());
        assertThat(subscribers()).isEqualTo(1);
    }

    @Test
    void loginToggleOrCredentialChangeClosesAllStreams() {
        broadcaster.subscribe(null);
        broadcaster.subscribe("s1");
        guard.onConfigChanged(change(auth -> auth.setRequireLoginToView(true)));
        assertThat(subscribers()).isZero();

        broadcaster.subscribe("s2");
        guard.onConfigChanged(change(auth -> {
            auth.setUsername("admin");
            auth.setPasswordHash("$2a$10$other");
        }));
        assertThat(subscribers()).isZero();
    }

    @Test
    void staleEventsAreIgnored() {
        ConfigChangedEvent older = change(auth -> auth.setRequireLoginToView(true));
        ConfigChangedEvent newer = change(auth -> { });
        guard.onConfigChanged(newer);
        broadcaster.subscribe(null);
        guard.onConfigChanged(older);
        assertThat(subscribers()).isEqualTo(1);
    }

    @Test
    void closeSessionOnlyEndsThatSessionsStreams() {
        assertThat(broadcaster.subscribe("s1").getTimeout()).as("finite, so access is re-checked")
                .isEqualTo(Duration.ofMinutes(30).toMillis());
        broadcaster.subscribe("s2");
        broadcaster.subscribe(null);
        broadcaster.closeSession("s1");
        assertThat(subscribers()).isEqualTo(2);
    }

    private int subscribers() {
        return broadcaster.subscriberCount();
    }
}
