package fr.enimaloc.blinder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BlinderApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlinderApplication.class, args);
    }

}
