package vn.nutrimom.config;

import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PregnancyTimeConfig {
    @Bean
    Clock pregnancyClock() {
        return Clock.system(ZoneOffset.UTC);
    }
}
