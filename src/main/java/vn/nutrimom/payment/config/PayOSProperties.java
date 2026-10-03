package vn.nutrimom.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payos")
public class PayOSProperties {
    private String clientId;
    private String apiKey;
    private String checksumKey;
    private String returnUrl = "http://localhost:5173/payment/success";
    private String cancelUrl = "http://localhost:5173/payment/cancel";

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && !"change-me".equalsIgnoreCase(clientId)
                && apiKey != null && !apiKey.isBlank() && !"change-me".equalsIgnoreCase(apiKey)
                && checksumKey != null && !checksumKey.isBlank() && !"change-me".equalsIgnoreCase(checksumKey);
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getChecksumKey() {
        return checksumKey;
    }

    public void setChecksumKey(String checksumKey) {
        this.checksumKey = checksumKey;
    }

    public String getReturnUrl() {
        return returnUrl;
    }

    public void setReturnUrl(String returnUrl) {
        this.returnUrl = returnUrl;
    }

    public String getCancelUrl() {
        return cancelUrl;
    }

    public void setCancelUrl(String cancelUrl) {
        this.cancelUrl = cancelUrl;
    }
}
