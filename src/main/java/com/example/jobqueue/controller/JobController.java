package com.example.jobqueue.controller;

import com.example.jobqueue.dto.CreateJobRequest;
import com.example.jobqueue.dto.JobResponse;
import com.example.jobqueue.dto.SubmitJobResponse;
import com.example.jobqueue.model.Job;
import com.example.jobqueue.service.JobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    /**
     * Accepts work for asynchronous processing. Returns 202 Accepted because the job is queued,
     * not completed — the client should poll GET /api/jobs/{id} for the final state.
     */
    @PostMapping
    public ResponseEntity<SubmitJobResponse> submitJob(@Valid @RequestBody CreateJobRequest request) {
        Job job = jobService.submit(request.getPayload());
        return ResponseEntity.accepted().body(SubmitJobResponse.from(job));
    }

    @GetMapping("/{id}")
    public JobResponse getJob(@PathVariable String id) {
        return JobResponse.from(jobService.getJob(id));
    }

    @GetMapping
    public List<JobResponse> listJobs() {
        return jobService.listAll().stream()
                .map(JobResponse::from)
                .toList();
    }
}
