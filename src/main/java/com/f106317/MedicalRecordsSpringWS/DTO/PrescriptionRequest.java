package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PrescriptionRequest(
        @NotNull(message = "Visit ID is required")
        Long visitId,
        // The column is TEXT, so the database imposes no practical limit of its own.
        @NotBlank(message = "Prescription note is required")
        @Size(max = 4000, message = "Prescription note cannot exceed 4000 characters")
        String note
) {}
