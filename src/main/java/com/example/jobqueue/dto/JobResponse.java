package com.example.jobqueue.dto;

import com.example.jobqueue.model.Job;
import com.example.jobqueue.model.JobStatus;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class JobResponse {

    String id;
    JobStatus status;
    String payload;
    String result;
    String errorMessage;
    int attemptCount;
    Instant createdAt;
    Instant updatedAt;
    Instant completedAt;

    public static JobResponse from(Job job) {
        return JobResponse.builder()
                .id(job.getId())
                .status(job.getStatus())
                .payload(job.getPayload())
                .result(job.getResult())
                .errorMessage(job.getErrorMessage())
                .attemptCount(job.getAttemptCount())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .completedAt(job.getCompletedAt())
                .build();
    }
}
