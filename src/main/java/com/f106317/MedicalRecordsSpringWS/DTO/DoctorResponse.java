package com.f106317.MedicalRecordsSpringWS.DTO;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record DoctorResponse(
        Long id,
        String keycloakId,
        String firstName,
        String lastName,
        @JsonProperty("isGP") boolean isGP,
        List<SpecializationResponse> specializations,

        // Only set when a Keycloak account was just created. Null on normal reads.
        String temporaryPassword,

        // Derived from audit log (not stored). True = self-registered, not yet approved (no DOCTOR role yet).
        boolean pending
) {
    public static DoctorResponse fromEntity(Doctor doctor) {
        List<SpecializationResponse> specs = doctor.getSpecializations() != null
                ? doctor.getSpecializations().stream()
                    .map(SpecializationResponse::fromEntity)
                    .toList()
                : List.of();
        return new DoctorResponse(
                doctor.getId(),
                doctor.getKeycloakId(),
                doctor.getFirstName(),
                doctor.getLastName(),
                doctor.isGP(),
                specs,
                null,
                false
        );
    }

    public DoctorResponse withTemporaryPassword(String password) {
        return new DoctorResponse(id, keycloakId, firstName, lastName, isGP, specializations, password, pending);
    }

    public DoctorResponse withPending(boolean stillPending) {
        return new DoctorResponse(id, keycloakId, firstName, lastName, isGP, specializations,
                temporaryPassword, stillPending);
    }
}
