package com.f106317.MedicalRecordsSpringWS.DTO;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Prescription;

public record PrescriptionResponse(
        Long id,
        Long visitId,
        String note
) {
    public static PrescriptionResponse fromEntity(Prescription prescription) {
        return new PrescriptionResponse(
                prescription.getId(),
                prescription.getVisit().getId(),
                prescription.getNote()
        );
    }
}
