package dev.soynx.heim.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.Link;
import dev.soynx.heim.config.model.Service;

class ProbeSchedulerTest {

    private final List<ProbeTarget> probed = new CopyOnWriteArrayList<>();
    private final HttpProber prober = new HttpProber() {
        @Override
        public LinkStatus probe(ProbeTarget target) {
            probed.add(target);
            return LinkStatus.up(200, 1, Instant.now());
        }
    };
    private final StatusRegistry registry = new StatusRegistry();
    private final StatusBroadcaster broadcaster = new StatusBroadcaster(registry);
    private final ProbeScheduler scheduler = new ProbeScheduler(null, prober, registry, broadcaster);

    @AfterEach
    void stop() throws InterruptedException {
        scheduler.destroy();
        broadcaster.destroy();
        prober.destroy();
    }

    @Test
    void probesEnabledLinksImmediatelyAndAggregates() {
        HeimConfig config = config(link("a", true, "http://a"), link("b", false, "http://b"));

        scheduler.sync(config);

        assertThat(scheduler.scheduledCount()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(2)).until(() -> !probed.isEmpty());
        await().atMost(Duration.ofSeconds(2))
                .until(() -> registry.snapshot().services().get("s1") == ServiceState.UP);
        assertThat(registry.snapshot().links()).containsOnlyKeys("a");
        assertThat(probed.getFirst().url()).isEqualTo("http://a");
    }

    @Test
    void reschedulesOnlyChangedLinksAndDropsRemovedOnes() {
        scheduler.sync(config(link("a", true, "http://a"), link("b", true, "http://b")));
        await().atMost(Duration.ofSeconds(2)).until(() -> probed.size() >= 2);
        probed.clear();

        // Same targets: nothing is rescheduled, so no immediate re-probe.
        scheduler.sync(config(link("a", true, "http://a"), link("b", true, "http://b")));
        // Changed URL for b, a removed.
        scheduler.sync(config(link("b", true, "http://b2")));

        await().atMost(Duration.ofSeconds(2)).until(() -> !probed.isEmpty());
        assertThat(probed).extracting(ProbeTarget::url).containsOnly("http://b2");
        assertThat(scheduler.scheduledCount()).isEqualTo(1);
        assertThat(registry.snapshot().links()).containsOnlyKeys("b");
    }

    @Test
    void olderConfigsAreIgnored() {
        HeimConfig newer = config(link("b", true, "http://b"));
        newer.setRevision(5);
        HeimConfig older = config(link("a", true, "http://a"));
        older.setRevision(4);

        scheduler.sync(newer);
        scheduler.sync(older);

        assertThat(scheduler.scheduledCount()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(2)).until(() -> registry.snapshot().links().containsKey("b"));
        assertThat(registry.snapshot().links()).containsOnlyKeys("b");
    }

    @Test
    void probeUrlOverridesLinkUrl() {
        Link link = link("a", true, "http://a");
        link.getProbe().setUrl("http://a/health");
        link.getProbe().setTimeoutSeconds(2);
        HeimConfig config = config(link);

        ProbeTarget target = ProbeTarget.of(config.getServices().getFirst(), link, config.getSettings().getProbe());

        assertThat(target.url()).isEqualTo("http://a/health");
        assertThat(target.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(target.interval()).isEqualTo(Duration.ofSeconds(60));
    }

    private static HeimConfig config(Link... links) {
        Service service = new Service();
        service.setId("s1");
        service.setLinks(List.of(links));
        HeimConfig config = new HeimConfig();
        config.setServices(List.of(service));
        return config;
    }

    private static Link link(String id, boolean probe, String url) {
        Link link = new Link();
        link.setId(id);
        link.setUrl(url);
        link.getProbe().setEnabled(probe);
        return link;
    }
}
