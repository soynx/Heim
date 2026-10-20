package dev.soynx.heim.probe;

import java.util.ArrayList;
import java.util.List;

import dev.soynx.heim.config.model.ProbeConfig;

/** Parsed {@code expectedStatus} spec such as {@code "200,204,300-399"}. */
public final class StatusMatcher {

    private static final StatusMatcher DEFAULT = parse(ProbeConfig.DEFAULT_EXPECTED_STATUS);

    private final List<int[]> ranges;

    private StatusMatcher(List<int[]> ranges) {
        this.ranges = ranges;
    }

    /** Parses a spec; null, blank or malformed specs fall back to {@value ProbeConfig#DEFAULT_EXPECTED_STATUS}. */
    public static StatusMatcher of(String spec) {
        if (spec == null || !spec.matches(ProbeConfig.EXPECTED_STATUS_PATTERN)) {
            return DEFAULT;
        }
        return parse(spec);
    }

    private static StatusMatcher parse(String spec) {
        List<int[]> ranges = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] bounds = part.split("-");
            int from = Integer.parseInt(bounds[0].trim());
            int to = bounds.length > 1 ? Integer.parseInt(bounds[1].trim()) : from;
            ranges.add(new int[] {Math.min(from, to), Math.max(from, to)});
        }
        return new StatusMatcher(List.copyOf(ranges));
    }

    public boolean matches(int statusCode) {
        for (int[] range : ranges) {
            if (statusCode >= range[0] && statusCode <= range[1]) {
                return true;
            }
        }
        return false;
    }
}
