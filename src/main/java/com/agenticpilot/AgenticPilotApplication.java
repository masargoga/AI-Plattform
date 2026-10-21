package com.agenticpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AgenticPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgenticPilotApplication.class, args);
    }
}
