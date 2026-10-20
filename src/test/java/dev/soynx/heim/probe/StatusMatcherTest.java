package dev.soynx.heim.probe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StatusMatcherTest {

    @Test
    void matchesSingleCodesAndRanges() {
        StatusMatcher matcher = StatusMatcher.of("200, 204 ,300-399");
        assertThat(matcher.matches(200)).isTrue();
        assertThat(matcher.matches(204)).isTrue();
        assertThat(matcher.matches(300)).isTrue();
        assertThat(matcher.matches(399)).isTrue();
        assertThat(matcher.matches(201)).isFalse();
        assertThat(matcher.matches(400)).isFalse();
    }

    @Test
    void reversedRangeIsNormalized() {
        assertThat(StatusMatcher.of("399-300").matches(301)).isTrue();
    }

    @Test
    void invalidOrMissingSpecFallsBackToDefault() {
        for (String spec : new String[] {null, "", "abc", "200-", "999"}) {
            StatusMatcher matcher = StatusMatcher.of(spec);
            assertThat(matcher.matches(200)).as(spec).isTrue();
            assertThat(matcher.matches(399)).as(spec).isTrue();
            assertThat(matcher.matches(404)).as(spec).isFalse();
        }
    }
}
