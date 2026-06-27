package com.example.jobqueue.controller;

import com.example.jobqueue.model.JobStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void postJobs_returns202WithJobId() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payload":"integration-test-job"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void getJob_withValidId_returnsJobDetails() throws Exception {
        String jobId = submitJob("detail-check");

        mockMvc.perform(get("/api/jobs/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.payload").value("detail-check"))
                .andExpect(jsonPath("$.attemptCount").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void getJob_withInvalidId_returns404() throws Exception {
        mockMvc.perform(get("/api/jobs/{id}", "non-existent-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Job not found: non-existent-id"))
                .andExpect(jsonPath("$.jobId").value("non-existent-id"));
    }

    @Test
    @Timeout(10)
    void fullFlow_submitAndPollUntilCompleted() throws Exception {
        String jobId = submitJob("full-flow");

        JobStatus terminalStatus = pollUntilTerminal(jobId, 10_000);

        assertThat(terminalStatus).isEqualTo(JobStatus.COMPLETED);

        mockMvc.perform(get("/api/jobs/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result").value("Processed: full-flow"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
    }

    @Test
    @Timeout(30)
    void concurrency_submitTwentyJobs_allReachTerminalState() throws Exception {
        int jobCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(jobCount);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < jobCount; i++) {
            String payload = "concurrent-" + i;
            futures.add(executor.submit(() -> submitJob(payload)));
        }

        List<String> jobIds = new ArrayList<>();
        for (Future<String> future : futures) {
            jobIds.add(future.get(5, TimeUnit.SECONDS));
        }
        executor.shutdownNow();

        long deadline = System.currentTimeMillis() + 25_000;
        for (String jobId : jobIds) {
            JobStatus status = pollUntilTerminal(jobId, deadline - System.currentTimeMillis());
            assertThat(status)
                    .as("Job %s should reach a terminal state", jobId)
                    .isIn(JobStatus.COMPLETED, JobStatus.FAILED);
        }
    }

    private String submitJob(String payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"" + payload + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("jobId").asText();
    }

    private JobStatus pollUntilTerminal(String jobId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 1);

        while (System.currentTimeMillis() < deadline) {
            MvcResult result = mockMvc.perform(get("/api/jobs/{id}", jobId))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            JobStatus status = JobStatus.valueOf(body.get("status").asText());

            if (status == JobStatus.COMPLETED || status == JobStatus.FAILED) {
                return status;
            }

            Thread.sleep(50);
        }

        throw new AssertionError("Timed out waiting for terminal status on job " + jobId);
    }
}
