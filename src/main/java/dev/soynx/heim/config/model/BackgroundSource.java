package dev.soynx.heim.config.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;

public enum BackgroundSource {
    @JsonProperty("none") @JsonEnumDefaultValue NONE,
    @JsonProperty("upload") UPLOAD,
    @JsonProperty("url") URL
}
