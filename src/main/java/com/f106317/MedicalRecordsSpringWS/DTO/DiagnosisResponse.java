package com.f106317.MedicalRecordsSpringWS.DTO;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Diagnosis;

public record DiagnosisResponse(
        Long id,
        Long visitId,
        String name
) {
    public static DiagnosisResponse fromEntity(Diagnosis diagnosis) {
        return new DiagnosisResponse(
                diagnosis.getId(),
                diagnosis.getVisit().getId(),
                diagnosis.getName()
        );
    }
}
