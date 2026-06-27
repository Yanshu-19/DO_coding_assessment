package com.example.jobqueue.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.worker")
public class AppProperties {

    private int poolSize = 3;
    private int maxAttempts = 3;
    private long jobTimeoutMs = 30_000;
    private long pollIntervalMs = 200;
    private long workSleepMinMs = 2_000;
    private long workSleepMaxMs = 5_000;
    private int failureRatePercent = 30;
}
