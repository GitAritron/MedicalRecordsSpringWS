package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Administrator-facing payload for creating or updating a patient.
 * Users registering themselves use {@link PatientSelfRegistrationRequest}, which cannot set keycloakId.
 */
public record PatientRequest(
        // Bounded to the width of patient.keycloak_id so an over-long value fails validation
        // with a 400 rather than reaching the database and surfacing as a constraint violation.
        @Size(max = 36, message = "Keycloak ID cannot exceed 36 characters")
        String keycloakId,
        @NotBlank(message = "EGN is required")
        @Pattern(regexp = "^\\d{10}$", message = "EGN must be exactly 10 digits")
        String egn,
        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name cannot exceed 100 characters")
        String firstName,
        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name cannot exceed 100 characters")
        String lastName,
        Long gpDoctorId
) {}
