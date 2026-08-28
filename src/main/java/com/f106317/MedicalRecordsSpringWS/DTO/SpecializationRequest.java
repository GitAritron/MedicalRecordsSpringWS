package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotBlank;

public record SpecializationRequest(
        @NotBlank(message = "Specialization name is required")
        String name
) {}
