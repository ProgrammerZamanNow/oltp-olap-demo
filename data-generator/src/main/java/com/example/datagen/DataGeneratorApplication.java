package com.example.datagen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DataGeneratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataGeneratorApplication.class, args);
    }
}
