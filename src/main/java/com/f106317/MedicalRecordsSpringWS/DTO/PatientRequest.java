package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Administrator-facing - for creating or updating a patient.
public record PatientRequest(
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
        Long gpDoctorId,

        // Optional: providing a username creates a Keycloak account with a one-time password.
        @Size(max = 255, message = "Username cannot exceed 255 characters")
        String username
) {
    public PatientRequest withKeycloakId(String newKeycloakId) {
        return new PatientRequest(newKeycloakId, egn, firstName, lastName, gpDoctorId, username);
    }
}
