package com.f106317.MedicalRecordsSpringWS.DTO;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;

public record PatientResponse(
        Long id,
        String keycloakId,
        String egn,
        String firstName,
        String lastName,
        Long gpDoctorId,
        String gpDoctorName,

        // Only set when a Keycloak account was just created. Null on normal reads.
        String temporaryPassword
) {
    public static PatientResponse fromEntity(Patient patient) {
        Long gpId = patient.getGp() != null ? patient.getGp().getId() : null;
        String gpName = patient.getGp() != null
                ? "Dr. " + patient.getGp().getFirstName() + " " + patient.getGp().getLastName()
                : null;
        return new PatientResponse(
                patient.getId(),
                patient.getKeycloakId(),
                patient.getEgn(),
                patient.getFirstName(),
                patient.getLastName(),
                gpId,
                gpName,
                null
        );
    }

    public PatientResponse withTemporaryPassword(String password) {
        return new PatientResponse(id, keycloakId, egn, firstName, lastName,
                gpDoctorId, gpDoctorName, password);
    }
}
