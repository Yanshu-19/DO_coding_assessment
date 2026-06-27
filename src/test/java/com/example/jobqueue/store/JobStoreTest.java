package com.example.jobqueue.store;

import com.example.jobqueue.model.Job;
import com.example.jobqueue.model.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JobStoreTest {

    private JobStore jobStore;

    @BeforeEach
    void setUp() {
        jobStore = new JobStore();
    }

    @Test
    void save_addsJobToMapAndQueue() {
        Job job = sampleJob("job-1");

        jobStore.save(job);

        assertThat(jobStore.findById("job-1")).contains(job);
        assertThat(jobStore.getQueueDepth()).isEqualTo(1);
    }

    @Test
    void takeNextJobId_blocksUntilJobIsAdded() throws Exception {
        AtomicReference<String> takenId = new AtomicReference<>();
        CountDownLatch consumerReady = new CountDownLatch(1);

        Thread consumer = new Thread(() -> {
            consumerReady.countDown();
            try {
                takenId.set(jobStore.takeNextJobId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        assertThat(consumerReady.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        assertThat(takenId.get()).isNull();

        jobStore.save(sampleJob("blocked-job"));

        consumer.join(2_000);
        assertThat(takenId.get()).isEqualTo("blocked-job");
    }

    @Test
    void concurrentSaves_fromTenThreads_allPersist() throws Exception {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<String> jobIds = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            String jobId = "concurrent-job-" + i;
            jobIds.add(jobId);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    jobStore.save(sampleJob(jobId));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(jobStore.findAll()).hasSize(threadCount);
        assertThat(jobStore.getQueueDepth()).isEqualTo(threadCount);
        jobIds.forEach(id -> assertThat(jobStore.findById(id)).isPresent());
    }

    private Job sampleJob(String id) {
        return Job.builder()
                .id(id)
                .payload("payload-" + id)
                .status(JobStatus.QUEUED)
                .attemptCount(0)
                .maxAttempts(3)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
