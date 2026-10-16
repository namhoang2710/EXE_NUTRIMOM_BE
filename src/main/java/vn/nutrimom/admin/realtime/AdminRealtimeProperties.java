package vn.nutrimom.admin.realtime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.admin.realtime")
public class AdminRealtimeProperties {
    @Min(1) @Max(1000) private int maxConnections=100;
    @Min(1) @Max(20) private int maxConnectionsPerUser=5;
    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int value) { maxConnections=value; }
    public int getMaxConnectionsPerUser() { return maxConnectionsPerUser; }
    public void setMaxConnectionsPerUser(int value) { maxConnectionsPerUser=value; }
}
