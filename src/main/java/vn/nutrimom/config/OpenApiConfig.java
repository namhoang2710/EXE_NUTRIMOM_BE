package vn.nutrimom.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
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

    /**
     * Sinh schema OpenAPI theo snake_case để Swagger UI khớp với JSON thật
     * (runtime dùng spring.jackson.property-naming-strategy=SNAKE_CASE).
     * Swagger-core dùng Jackson 2 nên phải cấu hình ObjectMapper riêng ở đây.
     */
    @Bean
    ModelResolver snakeCaseModelResolver() {
        return new ModelResolver(new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }
}
