package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Self-registration payload for a doctor applying for an account.
 * WHAT: Carries only the fields an applicant may state about themselves.
 * WHY: Omits keycloakId (taken from the verified JWT) and isGP. GP status decides whether a doctor
 * appears in the GP directory patients choose from, so only an administrator may grant it.
 */
public record DoctorSelfRegistrationRequest(
        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name cannot exceed 100 characters")
        String firstName,
        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name cannot exceed 100 characters")
        String lastName,
        @NotNull(message = "Specialization IDs are required")
        Set<Long> specializationIds
) {}
