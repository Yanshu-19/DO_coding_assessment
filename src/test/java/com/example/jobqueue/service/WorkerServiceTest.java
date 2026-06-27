package com.example.jobqueue.service;

import com.example.jobqueue.config.AppProperties;
import com.example.jobqueue.model.Job;
import com.example.jobqueue.model.JobStatus;
import com.example.jobqueue.store.JobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerServiceTest {

    @Mock
    private JobStore jobStore;

    private AppProperties properties;
    private WorkerService workerService;
    private Map<String, Job> jobs;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.setWorkSleepMinMs(0);
        properties.setWorkSleepMaxMs(0);
        workerService = new WorkerService(jobStore, properties);
        jobs = new ConcurrentHashMap<>();

        when(jobStore.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(jobs.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            jobs.put(job.getId(), cloneJob(job));
            return null;
        }).when(jobStore).updateJob(any());
    }

    @Test
    void processNextJob_transitionsQueuedToRunningToCompleted() {
        properties.setFailureRatePercent(0);
        Job job = queuedJob("job-success", 3);
        jobs.put(job.getId(), job);

        invokeProcessNextJob(job.getId());

        Job finalJob = jobs.get("job-success");
        assertThat(finalJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(finalJob.getAttemptCount()).isEqualTo(1);
        assertThat(finalJob.getResult()).isEqualTo("Processed: test-payload");
        assertThat(finalJob.getCompletedAt()).isNotNull();
    }

    @Test
    void processNextJob_transitionsQueuedToRunningToFailedToQueuedOnRetry() {
        properties.setFailureRatePercent(100);
        properties.setMaxAttempts(3);
        Job job = queuedJob("job-retry", 3);
        jobs.put(job.getId(), job);

        invokeProcessNextJob(job.getId());

        Job retriedJob = jobs.get("job-retry");
        assertThat(retriedJob.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(retriedJob.getAttemptCount()).isEqualTo(1);
        assertThat(retriedJob.getErrorMessage()).isEqualTo("Simulated processing failure");
        verify(jobStore).reEnqueueJobId("job-retry");
    }

    @Test
    void processNextJob_marksPermanentFailureAfterMaxAttempts() {
        properties.setFailureRatePercent(100);
        properties.setMaxAttempts(1);
        Job job = queuedJob("job-fail", 1);
        jobs.put(job.getId(), job);

        invokeProcessNextJob(job.getId());

        Job failedJob = jobs.get("job-fail");
        assertThat(failedJob.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failedJob.getAttemptCount()).isEqualTo(1);
        assertThat(failedJob.getErrorMessage()).isEqualTo("Simulated processing failure");
        assertThat(failedJob.getCompletedAt()).isNotNull();
        assertThat(failedJob.isTerminal()).isTrue();

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobStore, atLeastOnce()).updateJob(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Job::getStatus)
                .contains(JobStatus.RUNNING, JobStatus.FAILED);
    }

    private void invokeProcessNextJob(String jobId) {
        ReflectionTestUtils.invokeMethod(workerService, "processNextJob", jobId);
    }

    private Job queuedJob(String id, int maxAttempts) {
        return Job.builder()
                .id(id)
                .payload("test-payload")
                .status(JobStatus.QUEUED)
                .attemptCount(0)
                .maxAttempts(maxAttempts)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private Job cloneJob(Job source) {
        return Job.builder()
                .id(source.getId())
                .payload(source.getPayload())
                .status(source.getStatus())
                .result(source.getResult())
                .errorMessage(source.getErrorMessage())
                .attemptCount(source.getAttemptCount())
                .maxAttempts(source.getMaxAttempts())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .completedAt(source.getCompletedAt())
                .build();
    }
}
