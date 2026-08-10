package vn.nutrimom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NutriMomApplication {
    public static void main(String[] args) {
        SpringApplication.run(NutriMomApplication.class, args);
    }
}
