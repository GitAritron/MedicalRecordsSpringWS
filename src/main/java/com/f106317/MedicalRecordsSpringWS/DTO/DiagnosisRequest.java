package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DiagnosisRequest(
        @NotNull(message = "Visit ID is required")
        Long visitId,
        @NotBlank(message = "Diagnosis name is required")
        String name
) {}
