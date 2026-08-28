package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PrescriptionRequest(
        @NotNull(message = "Visit ID is required")
        Long visitId,
        @NotBlank(message = "Prescription note is required")
        String note
) {}
