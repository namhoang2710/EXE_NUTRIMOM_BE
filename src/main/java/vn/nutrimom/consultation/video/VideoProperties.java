package vn.nutrimom.consultation.video;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.consultation.video")
public class VideoProperties {
    private boolean enabled = true;
    private String url = "";
    private String apiKey = "";
    private String apiSecret = "";
    private Duration joinEarly = Duration.ofMinutes(5);
    private Duration endGrace = Duration.ofMinutes(5);
    private Duration tokenTtl = Duration.ofMinutes(5);
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getUrl() { return url; }
    public void setUrl(String value) { url = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String value) { apiSecret = value; }
    public Duration getJoinEarly() { return joinEarly; }
    public void setJoinEarly(Duration value) { joinEarly = value; }
    public Duration getEndGrace() { return endGrace; }
    public void setEndGrace(Duration value) { endGrace = value; }
    public Duration getTokenTtl() { return tokenTtl; }
    public void setTokenTtl(Duration value) { tokenTtl = value; }
    public boolean ready() {
        if (!enabled || url == null || apiKey == null || apiKey.isBlank()
                || apiSecret == null || apiSecret.length() < 32) return false;
        try {
            URI uri = URI.create(url);
            return "wss".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException e) { return false; }
    }
}
