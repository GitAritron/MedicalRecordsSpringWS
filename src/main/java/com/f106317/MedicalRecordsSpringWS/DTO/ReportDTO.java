package com.f106317.MedicalRecordsSpringWS.DTO;

import java.util.List;

public class ReportDTO {

    public record DiagnosisCount(String diagnosisName, Long count) {}

    public record DoctorVisitCount(Long doctorId, String doctorName, Long visitCount) {}

    public record GpDoctorPatientCount(Long doctorId, String doctorName, Long patientCount) {}

    public record SickLeaveMonthCount(int year, int month, Long count) {}

    public record DoctorSickLeaveCount(Long doctorId, String doctorName, Long sickLeaveCount) {}

    // Includes specific unpaid months so the user knows what to pay (not just a boolean).
    public record PatientInsuranceStatus(Long patientId, String patientName, String egn,
                                         boolean insured, List<UnpaidMonth> unpaidMonths) {}

    public record UnpaidMonth(int year, int month) {}
}
