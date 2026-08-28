package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.InsurancePaymentRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.Service.InsuranceService;
import com.f106317.MedicalRecordsSpringWS.Service.KeycloakAdminService;
import com.f106317.MedicalRecordsSpringWS.Service.PatientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for managing Patient entities and health insurance verification.
 * WHAT: Exposes endpoints for CRUD operations and insurance checking.
 * WHY: Role-based access control (@PreAuthorize) ensures that Patients can only access 
 * their own data, while Doctors and Admins can query patient records.
 */
@RestController
@RequestMapping("/api/patients")
@RequiredArgsConstructor
@Slf4j
public class PatientController {

    private final PatientService patientService;
    private final InsuranceService insuranceService;
    private final KeycloakAdminService keycloakAdminService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<List<PatientResponse>> getAllPatients() {
        return ResponseEntity.ok(patientService.getAllPatients());
    }

    /**
     * Retrieves a patient profile by Keycloak ID.
     * WHY: Used by the frontend SPA when a PATIENT logs in to load their medical history.
     */
    @GetMapping("/keycloak/{keycloakId}")
    @PreAuthorize("hasRole('PATIENT')")
    public ResponseEntity<PatientResponse> getPatientByKeycloakId(@PathVariable String keycloakId, Authentication authentication) {
        PatientResponse patient = patientService.getPatientByKeycloakId(keycloakId, authentication);
        return ResponseEntity.ok(patient);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'PATIENT')")
    public ResponseEntity<PatientResponse> getPatientById(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(patientService.getPatientById(id, authentication));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PatientResponse> createPatient(@Valid @RequestBody PatientRequest request, Authentication authentication) {
        PatientResponse created = patientService.createPatient(request, authentication);
        // Auto-assign PATIENT role in Keycloak for self-registering users
        if (request.keycloakId() != null && !request.keycloakId().isBlank()) {
            try {
                keycloakAdminService.assignRealmRole(request.keycloakId(), "PATIENT");
            } catch (Exception e) {
                log.warn("Could not assign PATIENT role in Keycloak: {}", e.getMessage());
            }
        }
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PatientResponse> updatePatient(@PathVariable Long id, @Valid @RequestBody PatientRequest request, Authentication authentication) {
        return ResponseEntity.ok(patientService.updatePatient(id, request, authentication));
    }

    @PatchMapping("/{id}/gp")
    @PreAuthorize("hasAnyRole('ADMIN', 'PATIENT')")
    public ResponseEntity<PatientResponse> updatePatientGp(@PathVariable Long id, @RequestParam Long gpDoctorId, Authentication authentication) {
        return ResponseEntity.ok(patientService.updatePatientGp(id, gpDoctorId, authentication));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePatient(@PathVariable Long id, Authentication authentication) {
        patientService.deletePatient(id, authentication);
        return ResponseEntity.noContent().build();
    }

    /**
     * Checks if a patient has paid health insurance in the last 6 months.
     * WHY: Fulfills the requirement " make a check if they have paid their health insurance over the last 6 months".
     */
    @GetMapping("/{id}/insurance")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'PATIENT')")
    public ResponseEntity<Boolean> checkInsurance(@PathVariable Long id, Authentication authentication) {
        patientService.getPatientById(id, authentication);
        return ResponseEntity.ok(insuranceService.hasContinuousInsurance(id));
    }

    /**
     * Records a health insurance contribution payment.
     */
    @PostMapping("/{id}/insurance")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<Void> recordInsurancePayment(@PathVariable Long id, @Valid @RequestBody InsurancePaymentRequest request, Authentication authentication) {
        if (!id.equals(request.patientId())) {
            throw new IllegalArgumentException("Path patient ID does not match request body patient ID");
        }
        insuranceService.recordPayment(request, authentication);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
