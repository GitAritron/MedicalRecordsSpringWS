package com.f106317.MedicalRecordsSpringWS.DTO;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

public record DoctorRequest(
        String keycloakId,
        @NotBlank(message = "First name is required")
        String firstName,
        @NotBlank(message = "Last name is required")
        String lastName,
        @JsonProperty("isGP") boolean isGP,
        @NotNull(message = "Specialization IDs are required")
        Set<Long> specializationIds
) {}
