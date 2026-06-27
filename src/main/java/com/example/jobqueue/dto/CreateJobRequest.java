package com.example.jobqueue.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateJobRequest {

    @NotBlank(message = "payload must not be blank")
    private String payload;
}
