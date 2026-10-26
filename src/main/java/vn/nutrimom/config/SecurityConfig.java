package vn.nutrimom.config;

import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.*;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.ratelimit.RateLimitKeyResolver;
import vn.nutrimom.common.ratelimit.RateLimitProperties;
import vn.nutrimom.common.ratelimit.RateLimiterStore;
import vn.nutrimom.security.*;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            ApiAuthenticationEntryPoint entryPoint, ApiAccessDeniedHandler denied,
            JwtAuthenticationConverter converter, RateLimitProperties rateLimitProperties,
            RateLimiterStore rateLimiterStore, RateLimitKeyResolver rateLimitKeyResolver,
            UserRepository users) throws Exception {
        // Chạy sau khi xác thực (trước AuthorizationFilter) để request đã đăng nhập được khóa theo user,
        // còn login/OTP (chưa xác thực) khóa theo IP. Khởi tạo tại chỗ, không để thành bean, tránh servlet
        // container tự đăng ký filter lần hai.
        RateLimitFilter rateLimitFilter =
                new RateLimitFilter(rateLimitProperties, rateLimiterStore, rateLimitKeyResolver);
        http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
                .addFilterBefore(rateLimitFilter, AuthorizationFilter.class)
                .addFilterAfter(new ActiveAdminFilter(users), AuthorizationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Only completion of an already authorized SSE request; never permit arbitrary ASYNC routes.
                        .requestMatchers(request -> request.getDispatcherType()==jakarta.servlet.DispatcherType.ASYNC
                                && "/api/v1/admin/realtime/stream".equals(request.getServletPath())
                                && Boolean.TRUE.equals(request.getAttribute("nutrimom.admin-sse-authorized"))).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/activate", "/api/v1/auth/resend-activation",
                                "/api/v1/auth/otp/request", "/api/v1/auth/otp/verify",
                                "/api/v1/auth/magic-link/request", "/api/v1/auth/magic-link/verify",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout",
                                "/api/v1/auth/password/forgot", "/api/v1/auth/password/reset",
                                "/api/v1/auth/password/reset/validate",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/files/*/content").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/consultation-video/webhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/analytics/traffic/visit").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/experts", "/api/v1/experts/*").permitAll()
                        // Trang giá phải xem được khi chưa đăng nhập, nếu không thì FE vẫn phải
                        // hardcode giá đúng cái mà spec mục 17 cấm.
                        .requestMatchers(HttpMethod.GET, "/api/v1/subscription-plans").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/knowledge/articles", "/api/v1/knowledge/articles/*").permitAll()
                        // Danh mục món là nội dung giới thiệu: mẹ bầu phải xem được trước khi quyết
                        // định tạo tài khoản. Chỉ mở ĐÚNG các path GET danh mục dưới đây — mọi
                        // endpoint cá nhân của module dinh dưỡng (hồ sơ ở /api/v1/me/..., gợi ý,
                        // thực đơn, nhật ký) vẫn cần token.
                        // Đánh giá web: trang công khai và tổng hợp sao. /reviews/me vẫn cần đăng nhập.
                        .requestMatchers(HttpMethod.GET, "/api/v1/reviews", "/api/v1/reviews/summary").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/recipes", "/api/v1/recipes/*").permitAll()
                        // Chip dị ứng và ô tìm nguyên liệu của onboarding đọc hai danh mục này.
                        // Chúng là dữ liệu seed dùng chung, không mang thông tin của ai.
                        .requestMatchers(HttpMethod.GET, "/api/v1/allergens", "/api/v1/ingredients").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied))
                .oauth2ResourceServer(rs -> rs.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    UserDetailsService userDetailsService(UserRepository users, PasswordEncoder encoder) {
        String unavailablePasswordHash = encoder.encode(UUID.randomUUID().toString());
        return username -> users.findByPhone(username)
                .or(() -> users.findByEmailIgnoreCase(username))
                .map(user -> User.withUsername(user.getPhone() != null ? user.getPhone() : user.getEmail())
                        .password(user.getPasswordHash() == null ? unavailablePasswordHash : user.getPasswordHash())
                        .authorities(user.getRoles().stream()
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList())
                        .disabled(user.getStatus() == UserStatus.DISABLED)
                        .accountLocked(user.getStatus() == UserStatus.LOCKED).build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Bean
    DaoAuthenticationProvider daoAuthenticationProvider(UserDetailsService service, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(service);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean AuthenticationManager authenticationManager(DaoAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean
    SecretKey jwtSecretKey(SecurityProperties properties) {
        byte[] bytes = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32)
            throw new IllegalStateException("NUTRIMOM_JWT_SECRET must contain at least 32 UTF-8 bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, SecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuer()));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) return List.of();
            return roles.stream().map(role -> (GrantedAuthority)
                    new SimpleGrantedAuthority("ROLE_" + role)).toList();
        });
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        // Origin của FE theo môi trường: localhost khi dev, domain đã deploy khi chạy thật.
        Set<String> patterns = new LinkedHashSet<>(allowedOrigins != null ? allowedOrigins : List.of());
        patterns.add("https://nutrimom.site");
        patterns.add("https://www.nutrimom.site");
        patterns.add("https://nutrimom-fe.vercel.app");
        patterns.add("https://*.vercel.app");
        patterns.add("http://localhost:*");
        patterns.add("http://127.0.0.1:*");
        config.setAllowedOriginPatterns(new ArrayList<>(patterns));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Thiếu một tên ở đây là trình duyệt CHẶN request ngay sau preflight, không có status code
        // nào để đọc: FE chỉ thấy "Failed to fetch". Idempotency-Key do api-client gắn cho checkout
        // và trợ lý — backend không đọc header này, nhưng vẫn phải khai cho qua CORS.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id",
                "X-Device-Id", "Accept-Language", "Idempotency-Key", "X-File-Id"));
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
