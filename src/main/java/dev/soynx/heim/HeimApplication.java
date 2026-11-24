package dev.soynx.heim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import dev.soynx.heim.net.dns.HeimAddressResolverProvider;
import dev.soynx.heim.net.dns.InvalidNetworkConfigException;
import dev.soynx.heim.net.dns.NetworkConfig;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HeimApplication {

    public static void main(String[] args) {
        installNetworkConfig();
        SpringApplication.run(HeimApplication.class, args);
    }

    private static void installNetworkConfig() {
        try {
            HeimAddressResolverProvider.install(NetworkConfig.fromEnv(System.getenv()));
        } catch (InvalidNetworkConfigException e) {
            System.err.println("Invalid network configuration - " + e.getMessage());
            System.exit(1);
        }
    }
}
