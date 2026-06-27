package com.example.jobqueue.service;

import com.example.jobqueue.config.AppProperties;
import com.example.jobqueue.exception.JobNotFoundException;
import com.example.jobqueue.model.Job;
import com.example.jobqueue.model.JobStatus;
import com.example.jobqueue.store.JobStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobStore jobStore;
    private final AppProperties properties;

    public Job submit(String payload) {
        Job job = Job.builder()
                .id(UUID.randomUUID().toString())
                .payload(payload)
                .status(JobStatus.QUEUED)
                .attemptCount(0)
                .maxAttempts(properties.getMaxAttempts())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        jobStore.save(job);

        log.info("event=job_state_transition jobId={} from=null to={} attemptCount={} maxAttempts={} queueDepth={}",
                job.getId(), JobStatus.QUEUED, job.getAttemptCount(), job.getMaxAttempts(),
                jobStore.getQueueDepth());

        return job;
    }

    public Job getJob(String id) {
        return jobStore.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));
    }

    public List<Job> listAll() {
        return jobStore.findAll();
    }

    public void transitionTo(String jobId, JobStatus newStatus) {
        Job job = getJob(jobId);
        JobStatus previous = job.getStatus();
        job.setStatus(newStatus);
        job.setUpdatedAt(Instant.now());
        jobStore.updateJob(job);
        log.info("event=job_state_transition jobId={} from={} to={} attemptCount={}",
                jobId, previous, newStatus, job.getAttemptCount());
    }

    public void markCompleted(String jobId, String result) {
        Job job = getJob(jobId);
        JobStatus previous = job.getStatus();
        Instant now = Instant.now();
        job.setStatus(JobStatus.COMPLETED);
        job.setResult(result);
        job.setErrorMessage(null);
        job.setUpdatedAt(now);
        job.setCompletedAt(now);
        jobStore.updateJob(job);
        log.info("event=job_state_transition jobId={} from={} to={} attemptCount={} result={}",
                jobId, previous, JobStatus.COMPLETED, job.getAttemptCount(), result);
    }

    public void markFailed(String jobId, String errorMessage) {
        Job job = getJob(jobId);
        JobStatus previous = job.getStatus();
        Instant now = Instant.now();
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(errorMessage);
        job.setUpdatedAt(now);
        if (!job.canRetry()) {
            job.setCompletedAt(now);
        }
        jobStore.updateJob(job);
        log.info("event=job_state_transition jobId={} from={} to={} attemptCount={} errorMessage={} terminal={}",
                jobId, previous, JobStatus.FAILED, job.getAttemptCount(), errorMessage, job.isTerminal());
    }

    public void incrementAttemptCount(String jobId) {
        Job job = getJob(jobId);
        job.setAttemptCount(job.getAttemptCount() + 1);
        job.setUpdatedAt(Instant.now());
        jobStore.updateJob(job);
        log.info("event=job_retry_scheduled jobId={} attemptCount={} maxAttempts={} canRetry={}",
                jobId, job.getAttemptCount(), job.getMaxAttempts(), job.canRetry());
    }

    public void requeue(String jobId) {
        Job job = getJob(jobId);
        jobStore.save(job);
        log.info("event=job_requeued jobId={} queueDepth={}", jobId, jobStore.getQueueDepth());
    }
}
