package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Specialization;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.SpecializationRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.SpecializationResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.DoctorRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.SpecializationRepository;
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
    private final AuditLogService auditLogService;

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
    public DoctorResponse createDoctor(DoctorRequest request) {
        return createDoctor(request, null);
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
        auditLogService.log(authentication, "CREATE", "Doctor", saved.getId());
        return DoctorResponse.fromEntity(saved);
    }

    /** Updates an existing doctor's details and specializations. */
    @Transactional
    public DoctorResponse updateDoctor(Long id, DoctorRequest request) {
        return updateDoctor(id, request, null);
    }

    @Transactional
    public DoctorResponse updateDoctor(Long id, DoctorRequest request, Authentication authentication) {
        Doctor doctor = findDoctorById(id);
        doctor.setFirstName(request.firstName());
        doctor.setLastName(request.lastName());
        doctor.setGP(request.isGP());
        if (request.keycloakId() != null && !request.keycloakId().isBlank()) {
            doctor.setKeycloakId(request.keycloakId().trim());
        }
        if (request.specializationIds() != null) {
            List<Specialization> specs = specializationRepository.findAllById(request.specializationIds());
            doctor.setSpecializations(new HashSet<>(specs));
        }
        Doctor saved = doctorRepository.save(doctor);
        auditLogService.log(authentication, "UPDATE", "Doctor", saved.getId());
        return DoctorResponse.fromEntity(saved);
    }

    /** Deletes a doctor by ID. Throws ResourceNotFoundException if not found. */
    @Transactional
    public void deleteDoctor(Long id) {
        deleteDoctor(id, null);
    }

    @Transactional
    public void deleteDoctor(Long id, Authentication authentication) {
        Doctor doctor = findDoctorById(id);
        doctorRepository.delete(doctor);
        auditLogService.log(authentication, "DELETE", "Doctor", id);
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
    Doctor findDoctorById(Long id) {
        return doctorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + id));
    }
}
