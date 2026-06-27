package com.example.jobqueue.exception;

import lombok.Getter;

@Getter
public class JobNotFoundException extends RuntimeException {

    private final String jobId;

    public JobNotFoundException(String jobId) {
        super("Job not found: " + jobId);
        this.jobId = jobId;
    }
}
