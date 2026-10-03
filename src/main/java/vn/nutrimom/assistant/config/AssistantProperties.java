package vn.nutrimom.assistant.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.assistant")
public class AssistantProperties {
    private boolean enabled = true;
    private String apiKey = "";
    private String model = "openai/gpt-oss-20b";
    private boolean zeroDataRetentionConfirmed;
    private Duration timeout = Duration.ofSeconds(35);
    private int dailyUserLimit = 20;
    private int dailyGlobalLimit = 100;
    private int dailyTokenBudget = 150000;
    private int retentionDays = 30;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public boolean isZeroDataRetentionConfirmed() { return zeroDataRetentionConfirmed; }
    public void setZeroDataRetentionConfirmed(boolean value) { zeroDataRetentionConfirmed = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
    public int getDailyUserLimit() { return dailyUserLimit; }
    public void setDailyUserLimit(int value) { dailyUserLimit = value; }
    public int getDailyGlobalLimit() { return dailyGlobalLimit; }
    public void setDailyGlobalLimit(int value) { dailyGlobalLimit = value; }
    public int getDailyTokenBudget() { return dailyTokenBudget; }
    public void setDailyTokenBudget(int value) { dailyTokenBudget = value; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int value) { retentionDays = value; }
    public boolean ready() {
        return enabled && apiKey != null && !apiKey.isBlank() && zeroDataRetentionConfirmed;
    }
}
