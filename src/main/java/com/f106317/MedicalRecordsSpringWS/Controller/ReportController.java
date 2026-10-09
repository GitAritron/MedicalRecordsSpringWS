package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import com.f106317.MedicalRecordsSpringWS.DTO.VisitResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PendingDoctorResponse;
import com.f106317.MedicalRecordsSpringWS.Service.DoctorService;
import com.f106317.MedicalRecordsSpringWS.Service.InsuranceService;
import com.f106317.MedicalRecordsSpringWS.Service.VisitService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
@RequiredArgsConstructor
public class ReportController {

    private final VisitService visitService;
    private final DoctorService doctorService;
    private final InsuranceService insuranceService;

    @GetMapping("/diagnoses/common")
    public ResponseEntity<List<ReportDTO.DiagnosisCount>> getMostCommonDiagnoses() {
        return ResponseEntity.ok(visitService.getMostCommonDiagnoses());
    }

    // Insurance status for all patients - used by staff to check coverage.
    @GetMapping("/patients/insurance")
    public ResponseEntity<List<ReportDTO.PatientInsuranceStatus>> getPatientInsuranceStatus() {
        return ResponseEntity.ok(insuranceService.getStatusForAllPatients());
    }

    @GetMapping("/diagnoses/names")
    public ResponseEntity<List<String>> getDistinctDiagnosisNames() {
        return ResponseEntity.ok(visitService.getDistinctDiagnosisNames());
    }

    // Doctors awaiting admin approval.
    @GetMapping("/pending-registrations")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PendingDoctorResponse>> getPendingRegistrations() {
        return ResponseEntity.ok(doctorService.getPendingRegistrations());
    }

    @GetMapping("/diagnoses/patients")
    public ResponseEntity<List<PatientResponse>> getPatientsByDiagnosis(
            @RequestParam String diagnosis) {
        return ResponseEntity.ok(visitService.getPatientsByDiagnosis(diagnosis));
    }

    @GetMapping("/doctors/visits")
    public ResponseEntity<List<ReportDTO.DoctorVisitCount>> getDoctorsWithVisitCount() {
        return ResponseEntity.ok(doctorService.getDoctorsWithVisitCount());
    }

    @GetMapping("/doctors/gp-patients")
    public ResponseEntity<List<ReportDTO.GpDoctorPatientCount>> getGpDoctorPatientCounts() {
        return ResponseEntity.ok(doctorService.getGpDoctorPatientCounts());
    }

    @GetMapping("/sickleaves/months")
    public ResponseEntity<List<ReportDTO.SickLeaveMonthCount>> getMonthsWithMostSickLeaves() {
        return ResponseEntity.ok(visitService.getMonthsWithMostSickLeaves());
    }

    @GetMapping("/sickleaves/doctors")
    public ResponseEntity<List<ReportDTO.DoctorSickLeaveCount>> getDoctorsWithMostSickLeaves() {
        return ResponseEntity.ok(visitService.getDoctorsWithMostSickLeaves());
    }

    @GetMapping("/visits/period")
    public ResponseEntity<List<VisitResponse>> getVisitsInPeriod(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ResponseEntity.ok(visitService.getVisitsInPeriod(start, end));
    }

    @GetMapping("/visits/doctor/period")
    public ResponseEntity<List<VisitResponse>> getVisitsByDoctorInPeriod(
            @RequestParam Long doctorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ResponseEntity.ok(visitService.getVisitsByDoctorInPeriod(doctorId, start, end));
    }

    @GetMapping("/visits/patients")
    public ResponseEntity<List<VisitResponse>> getVisitsByAllPatients() {
        return ResponseEntity.ok(visitService.getAllVisitsOrderedByPatient());
    }
}
