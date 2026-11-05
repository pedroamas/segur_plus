package com.segurplus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SegurPlusApplication {

    public static void main(String[] args) {
        SpringApplication.run(SegurPlusApplication.class, args);
    }
}
