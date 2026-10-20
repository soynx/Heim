package dev.soynx.heim.config.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Base for all config model classes. JSON properties this build does not know are kept in
 * {@link #getExtra()} and written back on save, so a config produced by a newer version
 * survives a round trip through an older one.
 */
public abstract class Extensible {

    private final Map<String, Object> extra = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getExtra() {
        return extra;
    }

    @JsonAnySetter
    public void putExtra(String name, Object value) {
        extra.put(name, value);
    }
}
