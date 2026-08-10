package vn.nutrimom.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI nutrimomOpenApi() {
        return new OpenAPI().info(new Info().title("NutriMom Backend API").version("v1")
                .description("API chăm sóc sức khỏe mẹ bầu, hồ sơ y tế, nội dung và tư vấn chuyên gia.")
                .contact(new Contact().name("NutriMom Team")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")));
    }
}
