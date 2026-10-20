package dev.soynx.heim.config.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum ProbeMethod {
    @JsonEnumDefaultValue GET,
    HEAD
}
