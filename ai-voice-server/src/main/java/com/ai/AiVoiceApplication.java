package com.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiVoiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiVoiceApplication.class, args);
    }
}
