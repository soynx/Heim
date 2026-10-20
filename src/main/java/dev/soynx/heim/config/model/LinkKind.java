package dev.soynx.heim.config.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Optional hint describing how a link reaches its service. */
public enum LinkKind {
    @JsonProperty("public") PUBLIC,
    @JsonProperty("domain") DOMAIN,
    @JsonProperty("hostname") HOSTNAME,
    @JsonProperty("ip") IP,
    @JsonProperty("vpn") VPN,
    @JsonProperty("other") @JsonEnumDefaultValue OTHER
}
