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
import java.util.stream.Collectors;

/**
 * Service layer for Visit operations.
 * WHAT: Handles the creation and management of medical visits, diagnoses, 
 * prescriptions, and sick leaves.
 * WHY: This class encapsulates the complex business rules of the medical system,
 * ensuring that doctors can only issue medical documents for visits they attended,
 * and patients can only view their own medical history.
 */
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

    /** Outer bounds for a plausible visit or sick-leave date; see validatePlausibleDate. */
    static final int MAX_HISTORY_YEARS = 100;
    static final int MAX_BOOKING_AHEAD_YEARS = 1;

    @Transactional(readOnly = true)
    public List<VisitResponse> getAllVisits() {
        return visitRepository.findAll().stream()
                .map(VisitResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves a visit by ID.
     * WHY: Uses `AuthorizationService.assertPatientCanAccessVisit` to guarantee
     * a patient cannot type in a random visit ID in the URL to see someone else's medical data.
     */
    @Transactional(readOnly = true)
    public VisitResponse getVisitById(Long id, Authentication authentication) {
        Visit visit = findVisitById(id);
        authorizationService.assertPatientCanAccessVisit(authentication, visit);
        return VisitResponse.fromEntity(visit);
    }

    /**
     * Records a new medical visit.
     * WHAT: Links a Doctor and a Patient to a specific date and time.
     * WHY: Security forces a check `assertDoctorCanCreateVisit` so a doctor 
     * cannot create a visit assigning a different doctor as the attending physician.
     */
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

    /** Deletes a visit by ID. */
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
        return visitRepository.findByPatientId(patientId).stream()
                .map(VisitResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<VisitResponse> getVisitsInPeriod(LocalDate start, LocalDate end) {
        return visitRepository.findVisitsInPeriod(start, end).stream()
                .map(VisitResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<VisitResponse> getVisitsByDoctorInPeriod(Long doctorId, LocalDate start, LocalDate end) {
        return visitRepository.findVisitsByDoctorInPeriod(doctorId, start, end).stream()
                .map(VisitResponse::fromEntity)
                .collect(Collectors.toList());
    }

    // ──────────────────────────────────────────────
    // Diagnosis CRUD
    // ──────────────────────────────────────────────

    /**
     * Issues a diagnosis for a specific visit.
     * WHY: Doctors must be authorized to modify the visit (`assertDoctorCanModifyVisit`), 
     * ensuring a doctor cannot add diagnoses to a visit conducted by another practitioner.
     */
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
                .collect(Collectors.toList());
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

    /**
     * Issues a sick leave for a visit.
     * WHAT: Creates a sick leave record specifying the start date and duration.
     * WHY: Meets the specification requirement that doctors can issue sick leaves 
     * during a visit. Relational constraints in the Entity map it `@OneToOne` to the visit.
     */
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
        return sickLeaveRepository.findMonthsWithMostSickLeaves().stream()
                .collect(Collectors.toList());
    }

    // ──────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────

    /**
     * Rejects dates that cannot plausibly describe a real clinical event.
     * WHY: these dates are only @NotNull at the DTO level, which accepts the year 9999. Visits and
     * sick leaves are legitimately dated both retrospectively and ahead, so neither @Past nor
     * @Future fits; a bounded range is the honest rule. The limits are deliberately generous —
     * they exist to catch typos and junk input, not to encode clinic policy.
     */
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

    /** Audit label for a visit, e.g. "Ivan Dimitrov / Dr. Georgi Ivanov, 2026-07-01". */
    private static String visitLabel(Visit visit) {
        return visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName()
                + " / Dr. " + visit.getDoctor().getFirstName() + " " + visit.getDoctor().getLastName()
                + ", " + visit.getVisitDate();
    }

    /** Audit label for a sick leave, e.g. "5 days from 2026-07-01 - Ivan Dimitrov / Dr. ...". */
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
