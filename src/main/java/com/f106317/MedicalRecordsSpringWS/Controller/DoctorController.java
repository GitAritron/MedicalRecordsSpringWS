package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.DoctorRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.SpecializationRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.SpecializationResponse;
import com.f106317.MedicalRecordsSpringWS.Service.DoctorService;
import com.f106317.MedicalRecordsSpringWS.Service.PatientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for managing Doctor entities.
 * WHAT: Provides endpoints for managing doctors, specializations, and patient assignments.
 * WHY: Admins can create/edit/delete doctors, while Doctors can look up their own profile
 * or view their assigned patients.
 */
@RestController
@RequestMapping("/api/doctors")
@RequiredArgsConstructor
public class DoctorController {

    private final DoctorService doctorService;
    private final PatientService patientService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'PATIENT')")
    public ResponseEntity<List<DoctorResponse>> getAllDoctors() {
        return ResponseEntity.ok(doctorService.getAllDoctors());
    }

    @GetMapping("/specializations")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'PATIENT')")
    public ResponseEntity<List<SpecializationResponse>> getAllSpecializations() {
        return ResponseEntity.ok(doctorService.getAllSpecializations());
    }

    @PostMapping("/specializations")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SpecializationResponse> createSpecialization(@Valid @RequestBody SpecializationRequest request) {
        return new ResponseEntity<>(doctorService.createSpecialization(request), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'PATIENT')")
    public ResponseEntity<DoctorResponse> getDoctorById(@PathVariable Long id) {
        return ResponseEntity.ok(doctorService.getDoctorById(id));
    }

    @GetMapping("/keycloak/{keycloakId}")
    @PreAuthorize("hasRole('DOCTOR')")
    public ResponseEntity<DoctorResponse> getDoctorByKeycloakId(@PathVariable String keycloakId) {
        return ResponseEntity.ok(doctorService.getDoctorByKeycloakId(keycloakId));
    }

    /**
     * Retrieves all patients assigned to a specific General Practitioner.
     * WHY: Fulfills requirement "List of patients who have a given GP".
     */
    @GetMapping("/{id}/patients")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<List<PatientResponse>> getPatientsByDoctor(@PathVariable Long id) {
        return ResponseEntity.ok(patientService.getPatientsByGp(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or isAuthenticated()")
    public ResponseEntity<DoctorResponse> createDoctor(@Valid @RequestBody DoctorRequest request, Authentication authentication) {
        return new ResponseEntity<>(doctorService.createDoctor(request, authentication), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DoctorResponse> updateDoctor(@PathVariable Long id, @Valid @RequestBody DoctorRequest request, Authentication authentication) {
        return ResponseEntity.ok(doctorService.updateDoctor(id, request, authentication));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteDoctor(@PathVariable Long id, Authentication authentication) {
        doctorService.deleteDoctor(id, authentication);
        return ResponseEntity.noContent().build();
    }
}
