package com.f106317.MedicalRecordsSpringWS.DTO;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.stream.Collectors;

public record DoctorResponse(
        Long id,
        String keycloakId,
        String firstName,
        String lastName,
        @JsonProperty("isGP") boolean isGP,
        List<SpecializationResponse> specializations,

        /**
         * Set only on the response to a request that created or reset a Keycloak account, so the
         * administrator can pass it on. Never persisted and never populated by {@link #fromEntity},
         * so ordinary reads always carry null.
         */
        String temporaryPassword
) {
    public static DoctorResponse fromEntity(Doctor doctor) {
        List<SpecializationResponse> specs = doctor.getSpecializations() != null
                ? doctor.getSpecializations().stream()
                    .map(SpecializationResponse::fromEntity)
                    .collect(Collectors.toList())
                : List.of();
        return new DoctorResponse(
                doctor.getId(),
                doctor.getKeycloakId(),
                doctor.getFirstName(),
                doctor.getLastName(),
                doctor.isGP(),
                specs,
                null
        );
    }

    /** Copy of this response carrying a freshly issued one-time password. */
    public DoctorResponse withTemporaryPassword(String password) {
        return new DoctorResponse(id, keycloakId, firstName, lastName, isGP, specializations, password);
    }
}
