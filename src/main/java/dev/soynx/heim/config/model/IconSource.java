package dev.soynx.heim.config.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;

public enum IconSource {
    @JsonProperty("favicon") @JsonEnumDefaultValue FAVICON,
    @JsonProperty("library") LIBRARY,
    @JsonProperty("url") URL,
    @JsonProperty("upload") UPLOAD
}
