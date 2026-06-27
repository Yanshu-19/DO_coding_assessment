package com.example.jobqueue.service;

import com.example.jobqueue.config.AppProperties;
import com.example.jobqueue.model.Job;
import com.example.jobqueue.model.JobStatus;
import com.example.jobqueue.store.JobStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerService {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final JobStore jobStore;
    private final AppProperties properties;

    private ExecutorService executorService;
    private final ConcurrentHashMap<String, ReentrantLock> jobLocks = new ConcurrentHashMap<>();

    @PostConstruct
    public void startWorkers() {
        int poolSize = properties.getPoolSize();
        executorService = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("job-worker-" + thread.getId());
            return thread;
        });
        log.info("event=worker_pool_started poolSize={}", poolSize);

        for (int workerIndex = 0; workerIndex < poolSize; workerIndex++) {
            executorService.submit(this::workerLoop);
        }
    }

    private void workerLoop() {
        log.info("event=worker_started thread={}", Thread.currentThread().getName());

        while (!Thread.currentThread().isInterrupted()) {
            try {
                String jobId = jobStore.takeNextJobId();
                processNextJob(jobId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("event=worker_interrupted thread={}", Thread.currentThread().getName());
                break;
            }
        }

        log.info("event=worker_stopped thread={}", Thread.currentThread().getName());
    }

    private void processNextJob(String jobId) {
        log.info("event=job_dequeued jobId={} queueDepth={}", jobId, jobStore.getQueueDepth());

        updateJobUnderLock(jobId, job -> {
            JobStatus previous = job.getStatus();
            job.setStatus(JobStatus.RUNNING);
            job.setAttemptCount(job.getAttemptCount() + 1);
            log.info("event=job_state_transition jobId={} from={} to={} attemptCount={} maxAttempts={}",
                    jobId, previous, JobStatus.RUNNING, job.getAttemptCount(), job.getMaxAttempts());
        });

        Job snapshot = jobStore.findById(jobId).orElse(null);
        if (snapshot == null) {
            log.error("event=job_missing jobId={}", jobId);
            return;
        }

        try {
            String result = processJob(snapshot);
            markCompleted(jobId, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("event=job_processing_interrupted jobId={}", jobId);
        } catch (JobProcessingException e) {
            handleFailure(jobId, e.getMessage());
        }
    }

    private void markCompleted(String jobId, String result) {
        updateJobUnderLock(jobId, job -> {
            JobStatus previous = job.getStatus();
            Instant now = Instant.now();
            job.setStatus(JobStatus.COMPLETED);
            job.setResult(result);
            job.setErrorMessage(null);
            job.setCompletedAt(now);
            log.info("event=job_state_transition jobId={} from={} to={} attemptCount={} result={}",
                    jobId, previous, JobStatus.COMPLETED, job.getAttemptCount(), result);
        });
    }

    private void handleFailure(String jobId, String errorMessage) {
        boolean[] shouldRequeue = { false };

        updateJobUnderLock(jobId, job -> {
            JobStatus previous = job.getStatus();

            if (job.canRetry()) {
                job.setStatus(JobStatus.QUEUED);
                job.setErrorMessage(errorMessage);
                shouldRequeue[0] = true;
                log.warn("event=job_state_transition jobId={} from={} to={} attemptCount={} canRetry=true errorMessage={}",
                        jobId, previous, JobStatus.QUEUED, job.getAttemptCount(), errorMessage);
                return;
            }

            Instant now = Instant.now();
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(errorMessage);
            job.setCompletedAt(now);
            log.warn("event=job_state_transition jobId={} from={} to={} attemptCount={} terminal=true errorMessage={}",
                    jobId, previous, JobStatus.FAILED, job.getAttemptCount(), errorMessage);
        });

        if (shouldRequeue[0]) {
            jobStore.reEnqueueJobId(jobId);
            log.info("event=job_requeued jobId={} queueDepth={}", jobId, jobStore.getQueueDepth());
        }
    }

    /**
     * Simulates work outside the lock so workers do not block each other during sleep.
     */
    private String processJob(Job job) throws InterruptedException, JobProcessingException {
        long minMs = properties.getWorkSleepMinMs();
        long maxMs = properties.getWorkSleepMaxMs();
        long sleepMs = minMs >= maxMs
                ? minMs
                : ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
        log.info("event=job_processing_started jobId={} sleepMs={}", job.getId(), sleepMs);
        if (sleepMs > 0) {
            Thread.sleep(sleepMs);
        }

        if (ThreadLocalRandom.current().nextInt(100) < properties.getFailureRatePercent()) {
            throw new JobProcessingException("Simulated processing failure");
        }

        log.info("event=job_processing_finished jobId={} sleepMs={}", job.getId(), sleepMs);
        return "Processed: " + job.getPayload();
    }

    /**
     * Per-job lock serializes read-modify-write across HTTP status reads and worker updates.
     */
    private void updateJobUnderLock(String jobId, Consumer<Job> mutator) {
        ReentrantLock lock = jobLocks.computeIfAbsent(jobId, id -> new ReentrantLock());
        lock.lock();
        try {
            Job job = jobStore.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
            mutator.accept(job);
            job.setUpdatedAt(Instant.now());
            jobStore.updateJob(job);
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("event=worker_pool_shutting_down timeoutSeconds={}", SHUTDOWN_TIMEOUT_SECONDS);
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("event=worker_pool_force_shutdown");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("event=worker_pool_stopped");
    }

    private static class JobProcessingException extends Exception {

        JobProcessingException(String message) {
            super(message);
        }
    }
}
