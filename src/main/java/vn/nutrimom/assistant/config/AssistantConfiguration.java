package vn.nutrimom.assistant.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import vn.nutrimom.assistant.service.AssistantStore;

@Configuration
@EnableScheduling
public class AssistantConfiguration {
    @Bean
    ApplicationRunner initializeAssistantQuota(AssistantStore store) { return args -> store.initializeQuota(); }
}
