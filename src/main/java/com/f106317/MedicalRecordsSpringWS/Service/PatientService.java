package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientSelfRegistrationRequest;
import com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PatientRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.DoctorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PatientService {

    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<PatientResponse> getAllPatients() {
        return patientRepository.findAll().stream()
                .map(PatientResponse::fromEntity)
                .toList();
    }

    // Maps a Keycloak UUID to the internal patient record.
    @Transactional(readOnly = true)
    public PatientResponse getPatientByKeycloakId(String keycloakId, Authentication authentication) {
        // Non-staff callers can only fetch their own profile.
        if (!authorizationService.isAdmin(authentication) && !authorizationService.isDoctor(authentication)) {
            String subject = authorizationService.extractSubject(authentication);
            if (subject == null || !subject.equals(keycloakId)) {
                throw new org.springframework.security.access.AccessDeniedException("Patients can access only their own profile");
            }
        }
        Patient patient = patientRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with Keycloak ID: " + keycloakId));
        return PatientResponse.fromEntity(patient);
    }

    @Transactional(readOnly = true)
    public PatientResponse getPatientById(Long id, Authentication authentication) {
        Patient patient = findPatientById(id);
        authorizationService.assertPatientCanAccessPatient(authentication, patient);
        return PatientResponse.fromEntity(patient);
    }

    @Transactional
    public PatientResponse createPatient(PatientRequest request, Authentication authentication) {
        Patient patient = new Patient();
        patient.setEgn(request.egn());
        patient.setFirstName(request.firstName());
        patient.setLastName(request.lastName());
        if (request.keycloakId() != null && !request.keycloakId().isBlank()) {
            patient.setKeycloakId(request.keycloakId().trim());
        }
        patient.setGp(resolveGpDoctor(request.gpDoctorId()));
        Patient saved = patientRepository.save(patient);
        auditLogService.log(authentication, "CREATE", "Patient", saved.getId(), patientLabel(saved));
        return PatientResponse.fromEntity(saved);
    }

    public record SelfRegistration(PatientResponse patient, boolean created) {}

    // Identity comes from the JWT, not the request body.
    @Transactional
    public SelfRegistration selfRegister(PatientSelfRegistrationRequest request, Authentication authentication) {
        String subject = authorizationService.extractSubject(authentication);
        if (subject == null || subject.isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "An authenticated identity is required to self-register");
        }
        // If already registered, return the existing record (idempotent).
        Optional<Patient> existing = patientRepository.findByKeycloakId(subject);
        if (existing.isPresent()) {
            return new SelfRegistration(PatientResponse.fromEntity(existing.get()), false);
        }
        // Explicit check so the user gets a helpful message instead of a generic constraint violation.
        if (patientRepository.existsByEgn(request.egn())) {
            throw new IllegalArgumentException(
                    "A patient record with this EGN already exists. "
                    + "Ask an administrator to link it to your account.");
        }

        Patient patient = new Patient();
        patient.setKeycloakId(subject);
        patient.setEgn(request.egn());
        patient.setFirstName(request.firstName());
        patient.setLastName(request.lastName());
        patient.setGp(resolveGpDoctor(request.gpDoctorId()));
        Patient saved = patientRepository.save(patient);
        auditLogService.log(authentication, "SELF_REGISTER", "Patient", saved.getId(), patientLabel(saved));
        return new SelfRegistration(PatientResponse.fromEntity(saved), true);
    }

    @Transactional
    public PatientResponse updatePatient(Long id, PatientRequest request, Authentication authentication) {
        Patient patient = findPatientById(id);

        // EGN is immutable after creation
        if (!patient.getEgn().equals(request.egn())) {
            throw new IllegalArgumentException("EGN cannot be changed after registration");
        }

        patient.setFirstName(request.firstName());
        patient.setLastName(request.lastName());
        applyAccountLink(patient, request.keycloakId());
        patient.setGp(resolveGpDoctor(request.gpDoctorId()));
        Patient saved = patientRepository.save(patient);
        auditLogService.log(authentication, "UPDATE", "Patient", saved.getId(), patientLabel(saved));
        return PatientResponse.fromEntity(saved);
    }

    // null = don't touch the link, empty = clear it, value = set it.
    private void applyAccountLink(Patient patient, String requestedKeycloakId) {
        if (requestedKeycloakId == null) {
            return;
        }
        String trimmed = requestedKeycloakId.trim();
        patient.setKeycloakId(trimmed.isEmpty() ? null : trimmed);
    }

    @Transactional
    public void deletePatient(Long id, Authentication authentication) {
        Patient patient = findPatientById(id);
        patientRepository.delete(patient);
        auditLogService.log(authentication, "DELETE", "Patient", id, patientLabel(patient));
    }

    // Only the patient themselves or an admin can change GP. Doctors cannot.
    @Transactional
    public PatientResponse updatePatientGp(Long patientId, Long gpDoctorId, Authentication authentication) {
        Patient patient = findPatientById(patientId);
        authorizationService.assertCanChangeGeneralPractitioner(authentication, patient);
        patient.setGp(resolveGpDoctor(gpDoctorId));
        Patient saved = patientRepository.save(patient);
        auditLogService.log(authentication, "UPDATE_GP", "Patient", saved.getId(),
                patientLabel(saved) + " -> " + (saved.getGp() == null ? "no GP"
                        : "Dr. " + saved.getGp().getFirstName() + " " + saved.getGp().getLastName()));
        return PatientResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public boolean hasProfileFor(String keycloakId) {
        return keycloakId != null && !keycloakId.isBlank()
                && patientRepository.findByKeycloakId(keycloakId).isPresent();
    }

    @Transactional(readOnly = true)
    public List<PatientResponse> getPatientsByGp(Long gpId) {
        return patientRepository.findByGpId(gpId).stream()
                .map(PatientResponse::fromEntity)
                .toList();
    }

    private static String patientLabel(Patient patient) {
        return patient.getFirstName() + " " + patient.getLastName();
    }

    Patient findPatientById(Long id) {
        return patientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + id));
    }

    // GP is optional (nullable FK). If provided, must actually be a GP doctor.
    private com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor resolveGpDoctor(Long doctorId) {
        if (doctorId == null) {
            return null;
        }
        var doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + doctorId));
        if (!doctor.isGP()) {
            throw new IllegalArgumentException("Doctor with id " + doctorId + " is not a General Practitioner");
        }
        return doctor;
    }
}
