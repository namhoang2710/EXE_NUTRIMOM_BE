package vn.nutrimom.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.RefreshTokenRepository;
import vn.nutrimom.config.SecurityProperties;

@Service
public class TokenService {
    private final JwtEncoder jwtEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecurityProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(JwtEncoder jwtEncoder, RefreshTokenRepository repository,
                        SecurityProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.refreshTokenRepository = repository;
        this.properties = properties;
    }

    public IssuedToken issue(UserEntity user, String deviceId) {
        Instant now = Instant.now();
        Instant accessExpiresAt = now.plus(properties.getAccessTokenTtl());
        List<String> roles = user.getRoles().stream().map(Enum::name).sorted().toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer()).subject(user.getId())
                .issuedAt(now).expiresAt(accessExpiresAt)
                .claim("phone", user.getPhone()).claim("roles", roles)
                .claim("type", "access").build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        String rawRefreshToken = generateRefreshToken();
        RefreshTokenEntity refreshToken = new RefreshTokenEntity();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(hash(rawRefreshToken));
        refreshToken.setDeviceId(normalizeDeviceId(deviceId));
        refreshToken.setExpiresAt(OffsetDateTime.ofInstant(
                now.plus(properties.getRefreshTokenTtl()), ZoneOffset.UTC));
        refreshTokenRepository.saveAndFlush(refreshToken);

        TokenBundle bundle = new TokenBundle(accessToken,
                properties.getAccessTokenTtl().toSeconds(), rawRefreshToken,
                properties.getRefreshTokenTtl().toSeconds(), "Bearer");
        return new IssuedToken(bundle, refreshToken);
    }

    public IssuedToken rotate(RefreshTokenEntity previous, UserEntity user, String requestedDeviceId) {
        String deviceId = requestedDeviceId == null || requestedDeviceId.isBlank()
                ? previous.getDeviceId() : requestedDeviceId;
        IssuedToken replacement = issue(user, deviceId);
        previous.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
        previous.setReplacedByTokenId(replacement.entity().getId());
        refreshTokenRepository.save(previous);
        return replacement;
    }

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeDeviceId(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record IssuedToken(TokenBundle bundle, RefreshTokenEntity entity) { }
}
