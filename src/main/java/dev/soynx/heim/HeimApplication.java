package dev.soynx.heim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HeimApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeimApplication.class, args);
    }
}
