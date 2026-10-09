package com.f106317.MedicalRecordsSpringWS.DTO;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Visit;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record VisitResponse(
        Long id,
        Long patientId,
        String patientName,
        Long doctorId,
        String doctorName,
        LocalDate visitDate,
        LocalTime visitTime,
        List<DiagnosisResponse> diagnoses,
        List<PrescriptionResponse> prescriptions,
        SickLeaveResponse sickLeave
) {
    public static VisitResponse fromEntity(Visit visit) {
        List<DiagnosisResponse> diagnosisList = visit.getDiagnoses() != null
                ? visit.getDiagnoses().stream()
                    .map(DiagnosisResponse::fromEntity)
                    .toList()
                : List.of();

        List<PrescriptionResponse> prescriptionList = visit.getPrescriptions() != null
                ? visit.getPrescriptions().stream()
                    .map(PrescriptionResponse::fromEntity)
                    .toList()
                : List.of();

        SickLeaveResponse sickLeaveResp = visit.getSickLeave() != null
                ? SickLeaveResponse.fromEntity(visit.getSickLeave())
                : null;

        return new VisitResponse(
                visit.getId(),
                visit.getPatient().getId(),
                visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName(),
                visit.getDoctor().getId(),
                visit.getDoctor().getFirstName() + " " + visit.getDoctor().getLastName(),
                visit.getVisitDate(),
                visit.getVisitTime(),
                diagnosisList,
                prescriptionList,
                sickLeaveResp
        );
    }
}
