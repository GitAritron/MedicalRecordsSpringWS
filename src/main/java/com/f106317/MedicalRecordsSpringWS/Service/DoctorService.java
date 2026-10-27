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
import com.f106317.MedicalRecordsSpringWS.Exception.ConflictException;
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

@Service
@RequiredArgsConstructor
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final SpecializationRepository specializationRepository;
    private final VisitRepository visitRepository;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final KeycloakAdminService keycloakAdminService;

    // Each doctor is tagged with its pending/approved status from the audit log.
    @Transactional(readOnly = true)
    public List<DoctorResponse> getAllDoctors() {
        java.util.Set<Long> pending = auditLogService.findPendingDoctorIds();
        return doctorRepository.findAll().stream()
                .map(DoctorResponse::fromEntity)
                .map(response -> response.withPending(pending.contains(response.id())))
                .toList();
    }

    @Transactional(readOnly = true)
    public DoctorResponse getDoctorById(Long id) {
        return DoctorResponse.fromEntity(findDoctorById(id))
                .withPending(auditLogService.findPendingDoctorIds().contains(id));
    }

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

    // Self-registration: identity from JWT, GP flag forced to false (admin decides that).
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
        Doctor saved = doctorRepository.save(doctor);
        auditLogService.log(authentication, AuditLogService.ACTION_SELF_REGISTER,
                AuditLogService.ENTITY_DOCTOR, saved.getId(), doctorLabel(saved));
        return DoctorResponse.fromEntity(saved);
    }

    // Resolves pending audit entries to doctor records. Skips deleted ones.
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
                .toList();
    }

    // Grants DOCTOR role in Keycloak. Propagates failure (unlike best-effort grants elsewhere).
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
        return DoctorResponse.fromEntity(doctor).withPending(false);
    }

    // Saving a pending doctor also logs approval, so the audit log matches the role grant.
    @Transactional
    public DoctorResponse updateDoctor(Long id, DoctorRequest request, Authentication authentication) {
        Doctor doctor = findDoctorById(id);
        boolean wasPending = auditLogService.findPendingDoctorIds().contains(id);
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

        boolean approvedHere = wasPending
                && saved.getKeycloakId() != null && !saved.getKeycloakId().isBlank();
        if (approvedHere) {
            auditLogService.log(authentication, AuditLogService.ACTION_APPROVE_DOCTOR,
                    AuditLogService.ENTITY_DOCTOR, saved.getId(), doctorLabel(saved));
        }
        return DoctorResponse.fromEntity(saved).withPending(wasPending && !approvedHere);
    }

    // null = don't touch, empty = clear, value = set. Same logic as PatientService.
    private void applyAccountLink(Doctor doctor, String requestedKeycloakId) {
        if (requestedKeycloakId == null) {
            return;
        }
        String trimmed = requestedKeycloakId.trim();
        doctor.setKeycloakId(trimmed.isEmpty() ? null : trimmed);
    }

    // Refuses if the doctor has visits - deleting would cascade-delete patients' medical history.
    @Transactional
    public void deleteDoctor(Long id, Authentication authentication) {
        Doctor doctor = findDoctorById(id);
        long recordedVisits = visitRepository.countByDoctorId(id);
        if (recordedVisits > 0) {
            throw new ConflictException(
                    "Dr. " + doctor.getFirstName() + " " + doctor.getLastName() + " has "
                    + recordedVisits + " recorded visit(s) and cannot be deleted, because that "
                    + "would erase those patients' medical history");
        }
        doctorRepository.delete(doctor);
        auditLogService.log(authentication, "DELETE", "Doctor", id, doctorLabel(doctor));
    }

    // Pending doctors are excluded - they have no role and zero visits.
    @Transactional(readOnly = true)
    public List<ReportDTO.DoctorVisitCount> getDoctorsWithVisitCount() {
        java.util.Set<Long> pending = auditLogService.findPendingDoctorIds();
        return doctorRepository.findDoctorsWithVisitCount().stream()
                .filter(row -> !pending.contains(row.doctorId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportDTO.GpDoctorPatientCount> getGpDoctorPatientCounts() {
        return doctorRepository.findGpDoctorsWithPatientCount();
    }

    @Transactional(readOnly = true)
    public boolean hasProfileFor(String keycloakId) {
        return keycloakId != null && !keycloakId.isBlank()
                && doctorRepository.findByKeycloakId(keycloakId).isPresent();
    }

    @Transactional(readOnly = true)
    public DoctorResponse getDoctorByKeycloakId(String keycloakId) {
        Doctor doctor = doctorRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with Keycloak ID: " + keycloakId));
        return DoctorResponse.fromEntity(doctor);
    }

    @Transactional(readOnly = true)
    public List<SpecializationResponse> getAllSpecializations() {
        return specializationRepository.findAll().stream()
                .map(SpecializationResponse::fromEntity)
                .toList();
    }

    @Transactional
    public SpecializationResponse createSpecialization(SpecializationRequest request,
                                                       Authentication authentication) {
        String name = request.name().trim();
        if (specializationRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("A specialization named \"" + name + "\" already exists");
        }
        Specialization spec = new Specialization();
        spec.setName(name);
        Specialization saved = specializationRepository.save(spec);
        auditLogService.log(authentication, "CREATE", "Specialization", saved.getId(), saved.getName());
        return SpecializationResponse.fromEntity(saved);
    }

    @Transactional
    public SpecializationResponse updateSpecialization(Long id, SpecializationRequest request,
                                                       Authentication authentication) {
        Specialization spec = findSpecializationById(id);
        String name = request.name().trim();
        if (specializationRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ConflictException("A specialization named \"" + name + "\" already exists");
        }
        spec.setName(name);
        Specialization saved = specializationRepository.save(spec);
        auditLogService.log(authentication, "UPDATE", "Specialization", saved.getId(), saved.getName());
        return SpecializationResponse.fromEntity(saved);
    }

    // Removes the specialization from all doctors who hold it, then deletes it.
    @Transactional
    public void deleteSpecialization(Long id, Authentication authentication) {
        Specialization spec = findSpecializationById(id);
        String name = spec.getName();
        List<Doctor> holders = doctorRepository.findAll().stream()
                .filter(doctor -> doctor.getSpecializations() != null
                        && doctor.getSpecializations().removeIf(held -> held.getId().equals(id)))
                .toList();
        doctorRepository.saveAll(holders);
        specializationRepository.delete(spec);
        auditLogService.log(authentication, "DELETE", "Specialization", id,
                holders.isEmpty() ? name : name + " - removed from " + holders.size() + " doctor(s)");
    }

    private Specialization findSpecializationById(Long id) {
        return specializationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Specialization not found with id: " + id));
    }

    // Used for audit trail
    private static String doctorLabel(Doctor doctor) {
        return "Dr. " + doctor.getFirstName() + " " + doctor.getLastName();
    }

    Doctor findDoctorById(Long id) {
        return doctorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + id));
    }
}
