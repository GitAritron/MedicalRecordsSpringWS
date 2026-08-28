package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
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

    @Transactional(readOnly = true)
    public PatientResponse getPatientByKeycloakId(String keycloakId) {
        return getPatientByKeycloakId(keycloakId, null);
    }

    /**
     * Retrieves a patient by their Keycloak Identity Provider UUID.
     * WHAT: Maps the external Keycloak token to the internal database record.
     * WHY: Ensures we securely fetch the logged-in user's data without relying on insecure sequential IDs.
     */
    @Transactional(readOnly = true)
    public PatientResponse getPatientByKeycloakId(String keycloakId, Authentication authentication) {
        if (authorizationService.isPatient(authentication)) {
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
    public PatientResponse getPatientById(Long id) {
        return getPatientById(id, null);
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

    @Transactional
    public PatientResponse createPatient(PatientRequest request) {
        return createPatient(request, null);
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
        auditLogService.log(authentication, "CREATE", "Patient", saved.getId());
        return PatientResponse.fromEntity(saved);
    }

    @Transactional
    public PatientResponse updatePatient(Long id, PatientRequest request) {
        return updatePatient(id, request, null);
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
        if (request.keycloakId() != null && !request.keycloakId().isBlank()) {
            patient.setKeycloakId(request.keycloakId().trim());
        }
        patient.setGp(resolveGpDoctor(request.gpDoctorId()));
        Patient saved = patientRepository.save(patient);
        auditLogService.log(authentication, "UPDATE", "Patient", saved.getId());
        return PatientResponse.fromEntity(saved);
    }

    @Transactional
    public void deletePatient(Long id) {
        deletePatient(id, null);
    }

    /**
     * Deletes a patient by ID.
     */
    @Transactional
    public void deletePatient(Long id, Authentication authentication) {
        Patient patient = findPatientById(id);
        patientRepository.delete(patient);
        auditLogService.log(authentication, "DELETE", "Patient", id);
    }

    /**
     * Updates a patient's assigned General Practitioner.
     * Patients can update their own GP; Admins can update any patient's GP.
     */
    @Transactional
    public PatientResponse updatePatientGp(Long patientId, Long gpDoctorId, Authentication authentication) {
        Patient patient = findPatientById(patientId);
        authorizationService.assertPatientCanAccessPatient(authentication, patient);
        patient.setGp(resolveGpDoctor(gpDoctorId));
        Patient saved = patientRepository.save(patient);
        auditLogService.log(authentication, "UPDATE_GP", "Patient", saved.getId());
        return PatientResponse.fromEntity(saved);
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
