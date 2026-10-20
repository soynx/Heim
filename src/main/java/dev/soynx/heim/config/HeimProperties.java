package dev.soynx.heim.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Application properties under {@code heim.*}. */
@ConfigurationProperties("heim")
public record HeimProperties(@DefaultValue("./data") Path dataDir) {
}
