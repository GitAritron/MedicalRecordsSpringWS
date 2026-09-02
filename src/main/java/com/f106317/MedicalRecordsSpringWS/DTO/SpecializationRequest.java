package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SpecializationRequest(
        @NotBlank(message = "Specialization name is required")
        @Size(max = 100, message = "Specialization name cannot exceed 100 characters")
        String name
) {}
