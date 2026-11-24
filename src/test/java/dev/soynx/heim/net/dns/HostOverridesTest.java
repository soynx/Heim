package dev.soynx.heim.net.dns;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class HostOverridesTest {

    private static final HostTarget EXACT = new HostTarget.Alias("exact");
    private static final HostTarget NARROW = new HostTarget.Alias("narrow");
    private static final HostTarget BROAD = new HostTarget.Alias("broad");

    private final HostOverrides overrides = new HostOverrides(
            Map.of("a.b.test", EXACT, "*.b.test", NARROW, "*.test", BROAD));

    @Test
    void exactBeatsWildcardAndNarrowBeatsBroad() {
        assertThat(overrides.find("a.b.test")).contains(EXACT);
        assertThat(overrides.find("x.b.test")).contains(NARROW);
        assertThat(overrides.find("x.y.b.test")).contains(NARROW);
        assertThat(overrides.find("b.test")).contains(BROAD);
    }

    @Test
    void wildcardDoesNotMatchItsOwnDomainOrSimilarNames() {
        assertThat(overrides.find("test")).isEmpty();
        assertThat(overrides.find("contest")).isEmpty();
        assertThat(overrides.find("a.latest")).isEmpty();
    }
}
