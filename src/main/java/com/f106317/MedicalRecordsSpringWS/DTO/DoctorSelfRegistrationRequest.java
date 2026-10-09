package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// No keycloakId (taken from verified JWT) and no isGP (admin decides that).
public record DoctorSelfRegistrationRequest(
        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name cannot exceed 100 characters")
        String firstName,
        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name cannot exceed 100 characters")
        String lastName
) {}
