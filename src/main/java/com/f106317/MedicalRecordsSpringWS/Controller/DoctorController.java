package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.DoctorRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorSelfRegistrationRequest;
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
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
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
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DoctorResponse> createDoctor(@Valid @RequestBody DoctorRequest request, Authentication authentication) {
        return new ResponseEntity<>(doctorService.createDoctor(request, authentication), HttpStatus.CREATED);
    }

    /**
     * Lets a signed-in user apply for a doctor account.
     * WHY: Separate from the admin endpoint above because the applicant may not choose their own
     * identity or GP status — the service derives both. The account stays inactive until an
     * administrator approves it through {@link #approveDoctor}.
     */
    @PostMapping("/self-register")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DoctorResponse> selfRegisterDoctor(@Valid @RequestBody DoctorSelfRegistrationRequest request,
                                                             Authentication authentication) {
        return new ResponseEntity<>(doctorService.selfRegister(request, authentication), HttpStatus.CREATED);
    }

    /**
     * Approves a self-registered doctor, granting them the DOCTOR role in Keycloak.
     * WHY: completes the self-registration flow inside the application. Without it an administrator
     * had to assign the role by hand in the Keycloak console, and the pending list never cleared
     * because nothing recorded that the approval had happened.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DoctorResponse> approveDoctor(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(doctorService.approveDoctor(id, authentication));
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
