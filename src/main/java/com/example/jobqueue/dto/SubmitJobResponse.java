package com.example.jobqueue.dto;

import com.example.jobqueue.model.Job;
import com.example.jobqueue.model.JobStatus;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SubmitJobResponse {

    String jobId;
    JobStatus status;

    public static SubmitJobResponse from(Job job) {
        return SubmitJobResponse.builder()
                .jobId(job.getId())
                .status(job.getStatus())
                .build();
    }
}
