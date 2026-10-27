package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Diagnosis;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Prescription;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.SickLeave;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Visit;
import com.f106317.MedicalRecordsSpringWS.DTO.DiagnosisRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.DiagnosisResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PrescriptionRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PrescriptionResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import com.f106317.MedicalRecordsSpringWS.DTO.SickLeaveRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.SickLeaveResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.VisitRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.VisitResponse;
import com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.DiagnosisRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.DoctorRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PatientRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PrescriptionRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.SickLeaveRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;


@Service
@RequiredArgsConstructor
public class VisitService {

    private final VisitRepository visitRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final SickLeaveRepository sickLeaveRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;

    // Date bounds for sanity-checking visits and sick leaves.
    static final int MAX_HISTORY_YEARS = 100;
    static final int MAX_BOOKING_AHEAD_YEARS = 1;

    @Transactional(readOnly = true)
    public List<VisitResponse> getAllVisits() {
        return visitRepository.findAllByOrderByVisitDateDescVisitTimeDesc().stream()
                .map(VisitResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<VisitResponse> getAllVisitsOrderedByPatient() {
        return visitRepository.findAllOrderByPatientNameAndDate().stream()
                .map(VisitResponse::fromEntity)
                .toList();
    }

    // Ensures patients can only access their own visits.
    @Transactional(readOnly = true)
    public VisitResponse getVisitById(Long id, Authentication authentication) {
        Visit visit = findVisitById(id);
        authorizationService.assertPatientCanAccessVisit(authentication, visit);
        return VisitResponse.fromEntity(visit);
    }

    // Doctors can only create visits for themselves (not assign another doctor).
    @Transactional
    public VisitResponse createVisit(VisitRequest request, Authentication authentication) {
        Patient patient = patientRepository.findById(request.patientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + request.patientId()));
        Doctor doctor = doctorRepository.findById(request.doctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + request.doctorId()));

        authorizationService.assertDoctorCanCreateVisit(authentication, doctor);
        validatePlausibleDate(request.visitDate(), "Visit date");

        if (visitRepository.existsByDoctorIdAndVisitDateAndVisitTime(doctor.getId(), request.visitDate(), request.visitTime())) {
            throw new IllegalArgumentException("Doctor is already booked for this date and time");
        }
        if (visitRepository.existsByPatientIdAndVisitDateAndVisitTime(patient.getId(), request.visitDate(), request.visitTime())) {
            throw new IllegalArgumentException("Patient already has an appointment scheduled for this date and time");
        }

        Visit visit = new Visit();
        visit.setPatient(patient);
        visit.setDoctor(doctor);
        visit.setVisitDate(request.visitDate());
        visit.setVisitTime(request.visitTime());
        Visit saved = visitRepository.save(visit);
        auditLogService.log(authentication, "CREATE", "Visit", saved.getId(), visitLabel(saved));
        return VisitResponse.fromEntity(saved);
    }

    @Transactional
    public VisitResponse updateVisit(Long id, VisitRequest request, Authentication authentication) {
        Visit existingVisit = findVisitById(id);
        authorizationService.assertDoctorCanModifyVisit(authentication, existingVisit);
        validatePlausibleDate(request.visitDate(), "Visit date");

        Patient patient = patientRepository.findById(request.patientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + request.patientId()));
        Doctor doctor = doctorRepository.findById(request.doctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + request.doctorId()));

        if (authorizationService.isDoctor(authentication)) {
            authorizationService.assertDoctorCanCreateVisit(authentication, doctor);
        }

        boolean slotChanged = !existingVisit.getVisitDate().equals(request.visitDate())
                || !existingVisit.getVisitTime().equals(request.visitTime())
                || !existingVisit.getDoctor().getId().equals(doctor.getId())
                || !existingVisit.getPatient().getId().equals(patient.getId());

        if (slotChanged) {
            if (!existingVisit.getDoctor().getId().equals(doctor.getId())
                    || !existingVisit.getVisitDate().equals(request.visitDate())
                    || !existingVisit.getVisitTime().equals(request.visitTime())) {
                if (visitRepository.existsByDoctorIdAndVisitDateAndVisitTime(doctor.getId(), request.visitDate(), request.visitTime())) {
                    throw new IllegalArgumentException("Doctor is already booked for this date and time");
                }
            }
            if (!existingVisit.getPatient().getId().equals(patient.getId())
                    || !existingVisit.getVisitDate().equals(request.visitDate())
                    || !existingVisit.getVisitTime().equals(request.visitTime())) {
                if (visitRepository.existsByPatientIdAndVisitDateAndVisitTime(patient.getId(), request.visitDate(), request.visitTime())) {
                    throw new IllegalArgumentException("Patient already has an appointment scheduled for this date and time");
                }
            }
        }

        existingVisit.setPatient(patient);
        existingVisit.setDoctor(doctor);
        existingVisit.setVisitDate(request.visitDate());
        existingVisit.setVisitTime(request.visitTime());
        Visit saved = visitRepository.save(existingVisit);
        auditLogService.log(authentication, "UPDATE", "Visit", saved.getId(), visitLabel(saved));
        return VisitResponse.fromEntity(saved);
    }

    @Transactional
    public void deleteVisit(Long id, Authentication authentication) {
        Visit visit = findVisitById(id);
        authorizationService.assertDoctorCanModifyVisit(authentication, visit);
        visitRepository.delete(visit);
        auditLogService.log(authentication, "DELETE", "Visit", id, visitLabel(visit));
    }

    @Transactional(readOnly = true)
    public List<VisitResponse> getVisitsByPatient(Long patientId, Authentication authentication) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + patientId));
        authorizationService.assertPatientCanAccessPatient(authentication, patient);
        return visitRepository.findByPatientIdOrderByVisitDateDescVisitTimeDesc(patientId).stream()
                .map(VisitResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<VisitResponse> getVisitsInPeriod(LocalDate start, LocalDate end) {
        return visitRepository.findVisitsInPeriod(start, end).stream()
                .map(VisitResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<VisitResponse> getVisitsByDoctorInPeriod(Long doctorId, LocalDate start, LocalDate end) {
        return visitRepository.findVisitsByDoctorInPeriod(doctorId, start, end).stream()
                .map(VisitResponse::fromEntity)
                .toList();
    }

    // ──────────────────────────────────────────────
    // Diagnosis CRUD
    // ──────────────────────────────────────────────

    // Only the visit's own doctor can add diagnoses.
    @Transactional
    public DiagnosisResponse createDiagnosis(DiagnosisRequest request, Authentication authentication) {
        Visit visit = findVisitById(request.visitId());
        authorizationService.assertDoctorCanModifyVisit(authentication, visit);
        Diagnosis diagnosis = new Diagnosis();
        diagnosis.setVisit(visit);
        diagnosis.setName(request.name());
        Diagnosis saved = diagnosisRepository.save(diagnosis);
        auditLogService.log(authentication, "CREATE", "Diagnosis", saved.getId(),
                saved.getName() + " - " + visitLabel(visit));
        return DiagnosisResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public DiagnosisResponse getDiagnosisById(Long id, Authentication authentication) {
        Diagnosis diagnosis = findDiagnosisById(id);
        authorizationService.assertPatientCanAccessVisit(authentication, diagnosis.getVisit());
        return DiagnosisResponse.fromEntity(diagnosis);
    }

    @Transactional
    public DiagnosisResponse updateDiagnosis(Long id, DiagnosisRequest request, Authentication authentication) {
        Diagnosis diagnosis = findDiagnosisById(id);
        authorizationService.assertDoctorCanModifyVisit(authentication, diagnosis.getVisit());
        diagnosis.setName(request.name());
        Diagnosis saved = diagnosisRepository.save(diagnosis);
        auditLogService.log(authentication, "UPDATE", "Diagnosis", saved.getId(),
                saved.getName() + " - " + visitLabel(saved.getVisit()));
        return DiagnosisResponse.fromEntity(saved);
    }

    @Transactional
    public void deleteDiagnosis(Long id, Authentication authentication) {
        Diagnosis diagnosis = findDiagnosisById(id);
        authorizationService.assertDoctorCanModifyVisit(authentication, diagnosis.getVisit());
        diagnosisRepository.deleteById(id);
        auditLogService.log(authentication, "DELETE", "Diagnosis", id,
                diagnosis.getName() + " - " + visitLabel(diagnosis.getVisit()));
    }

    @Transactional(readOnly = true)
    public List<ReportDTO.DiagnosisCount> getMostCommonDiagnoses() {
        return diagnosisRepository.findMostCommonDiagnoses();
    }

    @Transactional(readOnly = true)
    public List<PatientResponse> getPatientsByDiagnosis(String diagnosisName) {
        return diagnosisRepository.findPatientsByDiagnosisName(diagnosisName).stream()
                .map(PatientResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> getDistinctDiagnosisNames() {
        return diagnosisRepository.findDistinctDiagnosisNames();
    }

    // ──────────────────────────────────────────────
    // Prescription CRUD
    // ──────────────────────────────────────────────

    @Transactional
    public PrescriptionResponse createPrescription(PrescriptionRequest request, Authentication authentication) {
        Visit visit = findVisitById(request.visitId());
        authorizationService.assertDoctorCanModifyVisit(authentication, visit);
        Prescription prescription = new Prescription();
        prescription.setVisit(visit);
        prescription.setNote(request.note());
        Prescription saved = prescriptionRepository.save(prescription);
        auditLogService.log(authentication, "CREATE", "Prescription", saved.getId(),
                saved.getNote() + " - " + visitLabel(visit));
        return PrescriptionResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public PrescriptionResponse getPrescriptionById(Long id, Authentication authentication) {
        Prescription prescription = prescriptionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found with id: " + id));
        authorizationService.assertPatientCanAccessVisit(authentication, prescription.getVisit());
        return PrescriptionResponse.fromEntity(prescription);
    }

    @Transactional
    public PrescriptionResponse updatePrescription(Long id, PrescriptionRequest request, Authentication authentication) {
        Prescription prescription = prescriptionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found with id: " + id));
        authorizationService.assertDoctorCanModifyVisit(authentication, prescription.getVisit());
        prescription.setNote(request.note());
        Prescription saved = prescriptionRepository.save(prescription);
        auditLogService.log(authentication, "UPDATE", "Prescription", saved.getId(),
                saved.getNote() + " - " + visitLabel(saved.getVisit()));
        return PrescriptionResponse.fromEntity(saved);
    }

    @Transactional
    public void deletePrescription(Long id, Authentication authentication) {
        Prescription prescription = prescriptionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found with id: " + id));
        authorizationService.assertDoctorCanModifyVisit(authentication, prescription.getVisit());
        prescriptionRepository.deleteById(id);
        auditLogService.log(authentication, "DELETE", "Prescription", id,
                prescription.getNote() + " - " + visitLabel(prescription.getVisit()));
    }

    // ──────────────────────────────────────────────
    // Sick Leave CRUD
    // ──────────────────────────────────────────────

    @Transactional
    public SickLeaveResponse createSickLeave(SickLeaveRequest request, Authentication authentication) {
        Visit visit = findVisitById(request.visitId());
        authorizationService.assertDoctorCanModifyVisit(authentication, visit);
        validatePlausibleDate(request.validFrom(), "Sick leave start date");
        SickLeave sickLeave = new SickLeave();
        sickLeave.setVisit(visit);
        sickLeave.setValidFrom(request.validFrom());
        sickLeave.setValidForDays(request.validForDays());
        SickLeave saved = sickLeaveRepository.save(sickLeave);
        auditLogService.log(authentication, "CREATE", "SickLeave", saved.getId(), sickLeaveLabel(saved));
        return SickLeaveResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public SickLeaveResponse getSickLeaveById(Long id, Authentication authentication) {
        SickLeave sickLeave = findSickLeaveById(id);
        authorizationService.assertPatientCanAccessVisit(authentication, sickLeave.getVisit());
        return SickLeaveResponse.fromEntity(sickLeave);
    }

    @Transactional
    public SickLeaveResponse updateSickLeave(Long id, SickLeaveRequest request, Authentication authentication) {
        SickLeave sickLeave = findSickLeaveById(id);
        authorizationService.assertDoctorCanModifyVisit(authentication, sickLeave.getVisit());
        validatePlausibleDate(request.validFrom(), "Sick leave start date");
        sickLeave.setValidFrom(request.validFrom());
        sickLeave.setValidForDays(request.validForDays());
        SickLeave saved = sickLeaveRepository.save(sickLeave);
        auditLogService.log(authentication, "UPDATE", "SickLeave", saved.getId(), sickLeaveLabel(saved));
        return SickLeaveResponse.fromEntity(saved);
    }

    @Transactional
    public void deleteSickLeave(Long id, Authentication authentication) {
        SickLeave sickLeave = findSickLeaveById(id);
        authorizationService.assertDoctorCanModifyVisit(authentication, sickLeave.getVisit());
        sickLeaveRepository.deleteById(id);
        auditLogService.log(authentication, "DELETE", "SickLeave", id, sickLeaveLabel(sickLeave));
    }

    // ──────────────────────────────────────────────
    // Reports
    // ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ReportDTO.DoctorSickLeaveCount> getDoctorsWithMostSickLeaves() {
        return sickLeaveRepository.findDoctorsWithMostSickLeaves();
    }

    @Transactional(readOnly = true)
    public List<ReportDTO.SickLeaveMonthCount> getMonthsWithMostSickLeaves() {
        return sickLeaveRepository.findMonthsWithMostSickLeaves();
    }

    // ──────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────

    // Rejects dates that are obviously wrong (typos, junk input). Neither @Past nor @Future fits because visits can be backdated.
    private void validatePlausibleDate(LocalDate date, String label) {
        LocalDate today = LocalDate.now();
        if (date.isBefore(today.minusYears(MAX_HISTORY_YEARS))) {
            throw new IllegalArgumentException(
                    label + " cannot be more than " + MAX_HISTORY_YEARS + " years in the past");
        }
        if (date.isAfter(today.plusYears(MAX_BOOKING_AHEAD_YEARS))) {
            throw new IllegalArgumentException(
                    label + " cannot be more than " + MAX_BOOKING_AHEAD_YEARS + " year(s) in the future");
        }
    }

    private static String visitLabel(Visit visit) {
        return visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName()
                + " / Dr. " + visit.getDoctor().getFirstName() + " " + visit.getDoctor().getLastName()
                + ", " + visit.getVisitDate();
    }

    private static String sickLeaveLabel(SickLeave sickLeave) {
        return sickLeave.getValidForDays() + " days from " + sickLeave.getValidFrom()
                + " - " + visitLabel(sickLeave.getVisit());
    }

    private Visit findVisitById(Long id) {
        return visitRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Visit not found with id: " + id));
    }

    private Diagnosis findDiagnosisById(Long id) {
        return diagnosisRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Diagnosis not found with id: " + id));
    }

    private SickLeave findSickLeaveById(Long id) {
        return sickLeaveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sick leave not found with id: " + id));
    }
}
