package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DiagnosisRequest(
        @NotNull(message = "Visit ID is required")
        Long visitId,
        @NotBlank(message = "Diagnosis name is required")
        @Size(max = 255, message = "Diagnosis name cannot exceed 255 characters")
        String name
) {}
