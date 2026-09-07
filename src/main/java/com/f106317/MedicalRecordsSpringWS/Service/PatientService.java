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
import java.util.stream.Collectors;

/**
 * Service layer for Patient operations.
 * Handles the business logic for patient management, including security checks
 * to ensure that patients can only view their own data, while doctors and admins
 * have broader access.
 */
@Service
@RequiredArgsConstructor
public class PatientService {

    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;

    /**
     * Retrieves all patients.
     * WHY: Used by Admins to view the global directory of patients.
     */
    @Transactional(readOnly = true)
    public List<PatientResponse> getAllPatients() {
        return patientRepository.findAll().stream()
                .map(PatientResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves a patient by their Keycloak Identity Provider UUID.
     * WHAT: Maps the external Keycloak token to the internal database record.
     * WHY: Ensures we securely fetch the logged-in user's data without relying on insecure sequential IDs.
     */
    @Transactional(readOnly = true)
    public PatientResponse getPatientByKeycloakId(String keycloakId, Authentication authentication) {
        // Fails closed: anyone who is not staff must be asking for their own profile. The previous
        // form guarded on isPatient(...), which returns false for an absent or role-less token and
        // therefore skipped the ownership check entirely instead of refusing.
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

    /**
     * Retrieves a single patient by their database ID, performing authorization checks.
     * WHY: Doctors need to view specific patient profiles by ID. We assert that a Patient
     * cannot spoof this ID to view someone else's record.
     */
    @Transactional(readOnly = true)
    public PatientResponse getPatientById(Long id, Authentication authentication) {
        Patient patient = findPatientById(id);
        authorizationService.assertPatientCanAccessPatient(authentication, patient);
        return PatientResponse.fromEntity(patient);
    }

    /**
     * Creates a new patient record in the database.
     * WHAT: Maps the DTO to an Entity and validates the assigned General Practitioner (GP).
     * WHY: Patients must have a valid GP assigned at registration according to the requirements.
     */
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

    /**
     * Registers the calling user as a patient.
     * WHAT: Binds the new record to the Keycloak subject read from the verified token.
     * WHY: The identity must never come from the request body — otherwise any authenticated user
     * could create a profile bound to another person's account.
     */
    @Transactional
    public PatientResponse selfRegister(PatientSelfRegistrationRequest request, Authentication authentication) {
        String subject = authorizationService.extractSubject(authentication);
        if (subject == null || subject.isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "An authenticated identity is required to self-register");
        }
        if (patientRepository.findByKeycloakId(subject).isPresent()) {
            throw new IllegalArgumentException("A patient profile already exists for this account");
        }
        // Checked here rather than left to the EGN unique constraint: the constraint violation
        // surfaces as a generic "duplicate or constraint violation" 409, which tells someone whose
        // record an administrator already entered nothing about how to proceed. This is the common
        // case, not an edge one, and the way out is a link rather than a second record.
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
        return PatientResponse.fromEntity(saved);
    }

    /**
     * Updates an existing patient's details.
     * EGN is immutable after creation — attempting to change it will throw IllegalArgumentException.
     */
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

    /**
     * Applies a change to which Keycloak account this record belongs to.
     * WHAT: three states, because the field being absent and the field being empty mean different
     * things — absent leaves the link alone, empty clears it, a value sets it.
     * WHY the distinction: the administrator's form always sends the field, prefilled with the
     * current value, so an empty box there is a deliberate unlink. An API caller sending a partial
     * body omits the field entirely, and must not have the link silently torn out from under them.
     * The caller is responsible for revoking the previous holder's realm role.
     */
    private void applyAccountLink(Patient patient, String requestedKeycloakId) {
        if (requestedKeycloakId == null) {
            return;
        }
        String trimmed = requestedKeycloakId.trim();
        patient.setKeycloakId(trimmed.isEmpty() ? null : trimmed);
    }

    /**
     * Deletes a patient by ID.
     */
    @Transactional
    public void deletePatient(Long id, Authentication authentication) {
        Patient patient = findPatientById(id);
        patientRepository.delete(patient);
        auditLogService.log(authentication, "DELETE", "Patient", id, patientLabel(patient));
    }

    /**
     * Updates a patient's assigned General Practitioner.
     * Patients can update their own GP and administrators can update anyone's. Doctors cannot:
     * registering a patient with a GP is an administrative decision, not a clinical one.
     */
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

    /** Whether a patient record is already bound to this Keycloak subject. */
    @Transactional(readOnly = true)
    public boolean hasProfileFor(String keycloakId) {
        return keycloakId != null && !keycloakId.isBlank()
                && patientRepository.findByKeycloakId(keycloakId).isPresent();
    }

    /**
     * Retrieves all patients assigned to a specific General Practitioner.
     * WHY: Supports the "My Patients" functionality for logged-in doctors.
     */
    @Transactional(readOnly = true)
    public List<PatientResponse> getPatientsByGp(Long gpId) {
        return patientRepository.findByGpId(gpId).stream()
                .map(PatientResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /** Label used in the audit trail, e.g. "Ivan Dimitrov". */
    private static String patientLabel(Patient patient) {
        return patient.getFirstName() + " " + patient.getLastName();
    }

    Patient findPatientById(Long id) {
        return patientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + id));
    }

    /**
     * Helper method to validate and retrieve the GP doctor.
     * WHY: The specification requires that a patient is registered with a GP. 
     * This ensures the provided ID actually belongs to a doctor flagged as 'isGP = true'.
     */
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
