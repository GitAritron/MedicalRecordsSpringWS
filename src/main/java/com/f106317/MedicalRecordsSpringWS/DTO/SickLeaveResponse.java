package com.f106317.MedicalRecordsSpringWS.DTO;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.SickLeave;

import java.time.LocalDate;

public record SickLeaveResponse(
        Long id,
        Long visitId,
        LocalDate validFrom,
        int validForDays
) {
    public static SickLeaveResponse fromEntity(SickLeave sickLeave) {
        return new SickLeaveResponse(
                sickLeave.getId(),
                sickLeave.getVisit().getId(),
                sickLeave.getValidFrom(),
                sickLeave.getValidForDays()
        );
    }
}
