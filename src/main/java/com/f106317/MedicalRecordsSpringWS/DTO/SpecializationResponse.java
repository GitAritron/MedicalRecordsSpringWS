package com.f106317.MedicalRecordsSpringWS.DTO;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Specialization;

public record SpecializationResponse(
        Long id,
        String name
) {
    public static SpecializationResponse fromEntity(Specialization specialization) {
        return new SpecializationResponse(specialization.getId(), specialization.getName());
    }
}
