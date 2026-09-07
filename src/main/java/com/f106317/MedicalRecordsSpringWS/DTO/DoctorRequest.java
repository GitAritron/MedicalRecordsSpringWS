package com.f106317.MedicalRecordsSpringWS.DTO;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Administrator-facing payload for creating or updating a doctor.
 * Applicants registering themselves use {@link DoctorSelfRegistrationRequest}, which can set
 * neither keycloakId nor isGP.
 */
public record DoctorRequest(
        @Size(max = 36, message = "Keycloak ID cannot exceed 36 characters")
        String keycloakId,
        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name cannot exceed 100 characters")
        String firstName,
        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name cannot exceed 100 characters")
        String lastName,
        @JsonProperty("isGP") boolean isGP,
        @NotNull(message = "Specialization IDs are required")
        Set<Long> specializationIds,

        // See PatientRequest: optional, and optional on purpose. A blank username records a doctor
        // with no portal access; supplying one creates the Keycloak account and returns a one-time
        // password for the administrator to hand over.
        @Size(max = 255, message = "Username cannot exceed 255 characters")
        String username
) {
    /** Copy of this request bound to a Keycloak account the application has just created. */
    public DoctorRequest withKeycloakId(String newKeycloakId) {
        return new DoctorRequest(newKeycloakId, firstName, lastName, isGP, specializationIds, username);
    }
}
