package com.example.jobqueue.store;

import com.example.jobqueue.model.Job;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Thread-safe in-memory job store and work queue.
 *
 * <p><strong>Why {@link ConcurrentHashMap} instead of {@code HashMap} or {@code synchronized} blocks?</strong>
 * HTTP controller threads (submit/status) and worker threads (process/update) access job state concurrently.
 * A plain {@code HashMap} is not thread-safe: concurrent read/write can corrupt internal buckets or throw
 * {@code ConcurrentModificationException}. Wrapping a {@code HashMap} in {@code synchronized(this)} would serialize
 * every operation — including unrelated GET /jobs/{id} lookups — creating unnecessary contention under load.
 * {@code ConcurrentHashMap} uses lock striping so independent keys can be read and updated in parallel with
 * O(1) expected time, which matches our job-id-keyed access pattern without a single global lock.</p>
 *
 * <p><strong>Why a separate {@link BlockingQueue} instead of scanning the map or busy-wait polling?</strong>
 * The map answers "what is the state of job X?" (random access by ID). The queue answers "what should workers
 * process next?" (FIFO scheduling). Mixing both concerns in one structure would require scanning all jobs or
 * polling with {@code Thread.sleep}, wasting CPU while workers spin waiting for work. {@code LinkedBlockingQueue}
 * lets workers call {@link #takeNextJobId()}, which blocks efficiently until a job ID is available — no busy-wait
 * loop required. Producers ({@link #save}) and consumers ({@link #takeNextJobId}) are decoupled safely without
 * manual {@code wait/notify} or coarse-grained {@code synchronized} blocks.</p>
 */
@Component
public class JobStore {

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

    /**
     * Persists a new or updated job and enqueues its ID for worker processing.
     * Safe to call from HTTP threads; workers consume IDs via {@link #takeNextJobId()}.
     */
    public void save(Job job) {
        jobs.put(job.getId(), job);
        queue.add(job.getId());
    }

    /**
     * O(1) thread-safe lookup by job ID. Used by the status API and worker logic.
     */
    public Optional<Job> findById(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    /**
     * Returns a snapshot of all jobs currently in the store (debugging / observability).
     */
    public List<Job> findAll() {
        return List.copyOf(jobs.values());
    }

    /**
     * Writes the latest job state to the map only. Called after a worker has already dequeued the job ID;
     * the queue entry is not re-added here.
     */
    public void updateJob(Job job) {
        jobs.put(job.getId(), job);
    }

    /**
     * Re-enqueues an existing job ID for retry without modifying the map entry.
     */
    public void reEnqueueJobId(String jobId) {
        queue.add(jobId);
    }

    /**
     * Blocks until a job ID is available, then returns it. Workers should call this instead of polling.
     *
     * @throws InterruptedException if the worker thread is interrupted during shutdown
     */
    public String takeNextJobId() throws InterruptedException {
        return queue.take();
    }

    /**
     * Current number of job IDs waiting for workers. Useful for metrics and backpressure monitoring.
     */
    public int getQueueDepth() {
        return queue.size();
    }
}
