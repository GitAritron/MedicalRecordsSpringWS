package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Specialization;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorSelfRegistrationRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PendingDoctorResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.SpecializationRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.SpecializationResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.DoctorRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.SpecializationRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service layer for Doctor entity operations.
 * Handles CRUD, Keycloak identity mapping, and visit-count reporting.
 */
@Service
@RequiredArgsConstructor
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final SpecializationRepository specializationRepository;
    private final VisitRepository visitRepository;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final KeycloakAdminService keycloakAdminService;

    /** Retrieves all doctors and maps them to response DTOs. */
    @Transactional(readOnly = true)
    public List<DoctorResponse> getAllDoctors() {
        return doctorRepository.findAll().stream()
                .map(DoctorResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /** Retrieves a single doctor by database ID. */
    @Transactional(readOnly = true)
    public DoctorResponse getDoctorById(Long id) {
        return DoctorResponse.fromEntity(findDoctorById(id));
    }

    /** Creates a new doctor with optional specializations and Keycloak identity. */
    @Transactional
    public DoctorResponse createDoctor(DoctorRequest request, Authentication authentication) {
        Doctor doctor = new Doctor();
        doctor.setFirstName(request.firstName());
        doctor.setLastName(request.lastName());
        doctor.setGP(request.isGP());
        if (request.keycloakId() != null && !request.keycloakId().isBlank()) {
            doctor.setKeycloakId(request.keycloakId().trim());
        }
        if (request.specializationIds() != null && !request.specializationIds().isEmpty()) {
            List<Specialization> specs = specializationRepository.findAllById(request.specializationIds());
            doctor.setSpecializations(new HashSet<>(specs));
        }
        Doctor saved = doctorRepository.save(doctor);
        auditLogService.log(authentication, "CREATE", "Doctor", saved.getId(), doctorLabel(saved));
        return DoctorResponse.fromEntity(saved);
    }

    /**
     * Registers the calling user as a doctor, pending administrator approval.
     * WHAT: Binds the record to the caller's Keycloak subject and forces isGP to false.
     * WHY: Identity must come from the verified token, and GP status decides whether a doctor appears
     * in the directory patients pick their GP from — that is an administrator's call, not the applicant's.
     * The DOCTOR realm role is granted separately, by an administrator calling approveDoctor.
     */
    @Transactional
    public DoctorResponse selfRegister(DoctorSelfRegistrationRequest request, Authentication authentication) {
        String subject = authorizationService.extractSubject(authentication);
        if (subject == null || subject.isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "An authenticated identity is required to self-register");
        }
        if (doctorRepository.findByKeycloakId(subject).isPresent()) {
            throw new IllegalArgumentException("A doctor profile already exists for this account");
        }

        Doctor doctor = new Doctor();
        doctor.setKeycloakId(subject);
        doctor.setFirstName(request.firstName());
        doctor.setLastName(request.lastName());
        doctor.setGP(false);
        if (request.specializationIds() != null && !request.specializationIds().isEmpty()) {
            List<Specialization> specs = specializationRepository.findAllById(request.specializationIds());
            doctor.setSpecializations(new HashSet<>(specs));
        }
        Doctor saved = doctorRepository.save(doctor);
        auditLogService.log(authentication, AuditLogService.ACTION_SELF_REGISTER,
                AuditLogService.ENTITY_DOCTOR, saved.getId(), doctorLabel(saved));
        return DoctorResponse.fromEntity(saved);
    }

    /**
     * Lists doctors who registered themselves and are still awaiting approval.
     * WHY: resolves each audit entry to the doctor it refers to, so an administrator sees a name
     * rather than an entity id. Records deleted since registering are skipped rather than shown
     * as a dangling row.
     */
    @Transactional(readOnly = true)
    public List<PendingDoctorResponse> getPendingRegistrations() {
        return auditLogService.findPendingDoctorRegistrations().stream()
                .map(log -> doctorRepository.findById(log.getEntityId())
                        .map(doctor -> new PendingDoctorResponse(
                                doctor.getId(),
                                doctor.getFirstName() + " " + doctor.getLastName(),
                                doctor.getKeycloakId(),
                                log.getCreatedAt()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Approves a self-registered doctor by granting them the DOCTOR realm role in Keycloak.
     * WHY: previously an administrator had to leave the application and assign the role by hand in
     * the Keycloak console, and nothing recorded that they had done so — which is why the pending
     * list could never empty. The APPROVE_DOCTOR entry written here is what clears it.
     * <p>
     * Unlike self-registration, a Keycloak failure propagates: the caller asked for the role to be
     * granted, so silently continuing would report success for something that did not happen.
     */
    @Transactional
    public DoctorResponse approveDoctor(Long id, Authentication authentication) {
        Doctor doctor = findDoctorById(id);
        if (doctor.getKeycloakId() == null || doctor.getKeycloakId().isBlank()) {
            throw new IllegalArgumentException(
                    "This doctor record is not linked to a Keycloak account and cannot be approved");
        }
        keycloakAdminService.assignRealmRole(doctor.getKeycloakId(), "DOCTOR");
        auditLogService.log(authentication, AuditLogService.ACTION_APPROVE_DOCTOR,
                AuditLogService.ENTITY_DOCTOR, doctor.getId(), doctorLabel(doctor));
        return DoctorResponse.fromEntity(doctor);
    }

    /** Updates an existing doctor's details and specializations. */
    @Transactional
    public DoctorResponse updateDoctor(Long id, DoctorRequest request, Authentication authentication) {
        Doctor doctor = findDoctorById(id);
        doctor.setFirstName(request.firstName());
        doctor.setLastName(request.lastName());
        doctor.setGP(request.isGP());
        applyAccountLink(doctor, request.keycloakId());
        if (request.specializationIds() != null) {
            List<Specialization> specs = specializationRepository.findAllById(request.specializationIds());
            doctor.setSpecializations(new HashSet<>(specs));
        }
        Doctor saved = doctorRepository.save(doctor);
        auditLogService.log(authentication, "UPDATE", "Doctor", saved.getId(), doctorLabel(saved));
        return DoctorResponse.fromEntity(saved);
    }

    /**
     * Applies a change to which Keycloak account this record belongs to.
     * WHAT: three states — an absent field leaves the link alone, an empty one clears it, a value
     * sets it. See {@code PatientService.applyAccountLink}; the reasoning is identical.
     * The caller is responsible for revoking the previous holder's realm role.
     */
    private void applyAccountLink(Doctor doctor, String requestedKeycloakId) {
        if (requestedKeycloakId == null) {
            return;
        }
        String trimmed = requestedKeycloakId.trim();
        doctor.setKeycloakId(trimmed.isEmpty() ? null : trimmed);
    }

    /**
     * Deletes a doctor, provided they have no recorded visits.
     * WHY the guard: visit.doctor_id is declared ON DELETE CASCADE, so removing a doctor makes the
     * database silently delete their visits — and, cascading further, the diagnoses, prescriptions
     * and sick leaves hanging off them. That erases patient medical history as a side effect of a
     * staff record being removed, which contradicts the requirement to track each patient's
     * history. The records describe the patient, not the practitioner, so we refuse instead.
     * <p>
     * Contrast with the GP relationship, which the schema already handles correctly: deleting a
     * doctor merely sets patient.gp_doctor_id to NULL rather than deleting the patients.
     */
    @Transactional
    public void deleteDoctor(Long id, Authentication authentication) {
        Doctor doctor = findDoctorById(id);
        long recordedVisits = visitRepository.countByDoctorId(id);
        if (recordedVisits > 0) {
            throw new IllegalArgumentException(
                    "Dr. " + doctor.getFirstName() + " " + doctor.getLastName() + " has "
                    + recordedVisits + " recorded visit(s) and cannot be deleted, because that "
                    + "would erase those patients' medical history");
        }
        doctorRepository.delete(doctor);
        auditLogService.log(authentication, "DELETE", "Doctor", id, doctorLabel(doctor));
    }

    /** Report: retrieves all doctors with their total visit counts, ordered descending. */
    @Transactional(readOnly = true)
    public List<ReportDTO.DoctorVisitCount> getDoctorsWithVisitCount() {
        return doctorRepository.findDoctorsWithVisitCount().stream()
                .map(row -> new ReportDTO.DoctorVisitCount(
                        ((Doctor) row[0]).getId(),
                        ((Doctor) row[0]).getFirstName() + " " + ((Doctor) row[0]).getLastName(),
                        (Long) row[1]
                ))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ReportDTO.GpDoctorPatientCount> getGpDoctorPatientCounts() {
        return doctorRepository.findGpDoctorsWithPatientCount().stream()
                .map(row -> new ReportDTO.GpDoctorPatientCount(
                        ((Doctor) row[0]).getId(),
                        ((Doctor) row[0]).getFirstName() + " " + ((Doctor) row[0]).getLastName(),
                        (Long) row[1]
                ))
                .collect(Collectors.toList());
    }

    /** Whether a doctor record is already bound to this Keycloak subject. */
    @Transactional(readOnly = true)
    public boolean hasProfileFor(String keycloakId) {
        return keycloakId != null && !keycloakId.isBlank()
                && doctorRepository.findByKeycloakId(keycloakId).isPresent();
    }

    /** Looks up a doctor by their Keycloak Identity Provider UUID. */
    @Transactional(readOnly = true)
    public DoctorResponse getDoctorByKeycloakId(String keycloakId) {
        Doctor doctor = doctorRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with Keycloak ID: " + keycloakId));
        return DoctorResponse.fromEntity(doctor);
    }

    /** Retrieves all available specializations. */
    @Transactional(readOnly = true)
    public List<SpecializationResponse> getAllSpecializations() {
        return specializationRepository.findAll().stream()
                .map(SpecializationResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /** Creates a new specialization (Admin only). */
    @Transactional
    public SpecializationResponse createSpecialization(SpecializationRequest request) {
        Specialization spec = new Specialization();
        spec.setName(request.name().trim());
        Specialization saved = specializationRepository.save(spec);
        return SpecializationResponse.fromEntity(saved);
    }

    /** Internal helper: finds a Doctor entity or throws ResourceNotFoundException. */
    /** Label used in the audit trail, e.g. "Dr. Georgi Ivanov". */
    private static String doctorLabel(Doctor doctor) {
        return "Dr. " + doctor.getFirstName() + " " + doctor.getLastName();
    }

    Doctor findDoctorById(Long id) {
        return doctorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + id));
    }
}
