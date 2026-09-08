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

    /**
     * One patient's health-insurance standing, for the practice-wide view.
     * WHY it names the months rather than only a flag: the specification asks for a
     * <em>справка</em> — a check someone reads — and "not insured" on its own does not say whether
     * a patient missed one month or has never paid, which is the difference between a reminder and
     * a refusal.
     */
    public record PatientInsuranceStatus(Long patientId, String patientName, String egn,
                                         long coveredMonths, int requiredMonths, boolean insured) {}
}
