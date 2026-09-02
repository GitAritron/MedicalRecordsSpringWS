package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-registration payload for a patient.
 * WHAT: Carries only the fields a user may state about themselves.
 * WHY: Deliberately omits keycloakId — the identity is taken from the caller's verified JWT and
 * never from the request body, so a user cannot bind a profile to someone else's account.
 */
public record PatientSelfRegistrationRequest(
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
