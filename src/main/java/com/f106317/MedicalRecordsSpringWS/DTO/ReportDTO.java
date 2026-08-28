package com.f106317.MedicalRecordsSpringWS.DTO;

/**
 * Report DTOs for typed, readable API responses instead of Object[].
 */
public class ReportDTO {

    public record DiagnosisCount(String diagnosisName, Long count) {}

    public record DoctorVisitCount(Long doctorId, String doctorName, Long visitCount) {}

    public record GpDoctorPatientCount(Long doctorId, String doctorName, Long patientCount) {}

    public record SickLeaveMonthCount(int year, int month, Long count) {}

    public record DoctorSickLeaveCount(Long doctorId, String doctorName, Long sickLeaveCount) {}
}
