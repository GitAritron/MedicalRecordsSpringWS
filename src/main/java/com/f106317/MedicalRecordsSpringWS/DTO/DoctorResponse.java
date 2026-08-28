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
        List<SpecializationResponse> specializations
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
                specs
        );
    }
}
