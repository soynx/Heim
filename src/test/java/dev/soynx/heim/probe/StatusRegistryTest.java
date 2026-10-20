package dev.soynx.heim.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class StatusRegistryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final LinkStatus UP = LinkStatus.up(200, 5, NOW);
    private static final LinkStatus DOWN = LinkStatus.down(null, null, NOW, "Connection refused");

    @Test
    void aggregatesServiceState() {
        assertThat(ServiceState.aggregate(List.of())).isEqualTo(ServiceState.UNKNOWN);
        assertThat(ServiceState.aggregate(List.of(LinkStatus.UNKNOWN))).isEqualTo(ServiceState.UNKNOWN);
        assertThat(ServiceState.aggregate(List.of(UP, UP))).isEqualTo(ServiceState.UP);
        assertThat(ServiceState.aggregate(List.of(DOWN, DOWN))).isEqualTo(ServiceState.DOWN);
        assertThat(ServiceState.aggregate(List.of(UP, DOWN))).isEqualTo(ServiceState.DEGRADED);
        assertThat(ServiceState.aggregate(List.of(UP, LinkStatus.UNKNOWN))).isEqualTo(ServiceState.UP);
    }

    @Test
    void newLinksStartUnknownAndAppearInSnapshot() {
        StatusRegistry registry = new StatusRegistry();
        assertThat(registry.retain(Map.of("s1", List.of("a", "b")))).isTrue();

        StatusSnapshot snapshot = registry.snapshot();
        assertThat(snapshot.links()).containsEntry("a", LinkStatus.UNKNOWN).containsEntry("b", LinkStatus.UNKNOWN);
        assertThat(snapshot.services()).containsEntry("s1", ServiceState.UNKNOWN);
    }

    @Test
    void recordEmitsUpdateOnlyOnStateOrCodeChange() {
        StatusRegistry registry = new StatusRegistry();
        registry.retain(Map.of("s1", List.of("a", "b")));

        Optional<StatusUpdate> first = registry.record("s1", "a", UP);
        assertThat(first).hasValueSatisfying(u -> {
            assertThat(u.linkId()).isEqualTo("a");
            assertThat(u.service()).isEqualTo(ServiceState.UP);
        });
        assertThat(registry.record("s1", "a", LinkStatus.up(200, 99, NOW.plusSeconds(60)))).isEmpty();
        assertThat(registry.record("s1", "a", LinkStatus.up(204, 5, NOW))).isPresent();

        assertThat(registry.record("s1", "b", DOWN))
                .hasValueSatisfying(u -> assertThat(u.service()).isEqualTo(ServiceState.DEGRADED));
        // Latest result is stored even when nothing is broadcast.
        assertThat(registry.snapshot().links().get("a").statusCode()).isEqualTo(204);
    }

    @Test
    void retainDropsRemovedLinksAndIgnoresTheirLateResults() {
        StatusRegistry registry = new StatusRegistry();
        registry.retain(Map.of("s1", List.of("a", "b")));
        registry.record("s1", "a", UP);

        assertThat(registry.retain(Map.of("s1", List.of("a")))).isTrue();
        assertThat(registry.record("s1", "b", DOWN)).isEmpty();
        assertThat(registry.snapshot().links()).containsOnlyKeys("a").containsEntry("a", UP);
        assertThat(registry.retain(Map.of("s1", List.of("a")))).isFalse();

        registry.retain(Map.of());
        assertThat(registry.snapshot().links()).isEmpty();
        assertThat(registry.snapshot().services()).isEmpty();
    }
}
