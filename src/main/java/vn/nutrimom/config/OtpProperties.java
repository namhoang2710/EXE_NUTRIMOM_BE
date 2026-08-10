package vn.nutrimom.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.otp")
public class OtpProperties {
    private int codeLength = 6;
    private Duration ttl = Duration.ofMinutes(5);
    private Duration resendCooldown = Duration.ofSeconds(45);
    private int maxAttempts = 5;
    private boolean exposeDebugCode = true;
    private String hmacSecret;

    public int getCodeLength() { return codeLength; }
    public void setCodeLength(int codeLength) { this.codeLength = codeLength; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public Duration getResendCooldown() { return resendCooldown; }
    public void setResendCooldown(Duration resendCooldown) { this.resendCooldown = resendCooldown; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public boolean isExposeDebugCode() { return exposeDebugCode; }
    public void setExposeDebugCode(boolean exposeDebugCode) { this.exposeDebugCode = exposeDebugCode; }
    public String getHmacSecret() { return hmacSecret; }
    public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
}
