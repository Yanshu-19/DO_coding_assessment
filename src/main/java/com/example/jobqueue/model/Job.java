package com.example.jobqueue.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Job {

    private String id;
    private JobStatus status;
    private String payload;
    private String result;
    private String errorMessage;
    private int attemptCount;
    private int maxAttempts;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public boolean canRetry() {
        return attemptCount < maxAttempts;
    }

    public boolean isTerminal() {
        if (status == JobStatus.COMPLETED) {
            return true;
        }
        if (status == JobStatus.FAILED) {
            return !canRetry();
        }
        return false;
    }
}
