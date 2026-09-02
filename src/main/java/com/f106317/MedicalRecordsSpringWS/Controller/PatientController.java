package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.InsurancePaymentRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientSelfRegistrationRequest;
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
     * WHY: Used by the SPA when a patient signs in, to resolve their own record before loading
     * their history. Administrators are admitted too, so that staff can resolve an account the
     * same way; the service still refuses anyone else asking for a profile that is not theirs.
     */
    @GetMapping("/keycloak/{keycloakId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PATIENT')")
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
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PatientResponse> createPatient(@Valid @RequestBody PatientRequest request, Authentication authentication) {
        PatientResponse created = patientService.createPatient(request, authentication);
        // An administrator may link the new record to an existing Keycloak account and grant it the role.
        if (created.keycloakId() != null && !created.keycloakId().isBlank()) {
            assignPatientRoleQuietly(created.keycloakId());
        }
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    /**
     * Lets a signed-in user create their own patient profile.
     * WHY: Separate from the admin endpoint above because the caller may not choose which Keycloak
     * account the record binds to — the service takes that from the verified token.
     */
    @PostMapping("/self-register")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PatientResponse> selfRegisterPatient(@Valid @RequestBody PatientSelfRegistrationRequest request,
                                                               Authentication authentication) {
        PatientResponse created = patientService.selfRegister(request, authentication);
        assignPatientRoleQuietly(created.keycloakId());
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    /**
     * Grants the PATIENT realm role, logging rather than failing if Keycloak is unreachable.
     * WHY: The profile is already persisted at this point; an administrator can grant the role
     * manually, so a Keycloak outage should not fail an otherwise successful registration.
     */
    private void assignPatientRoleQuietly(String keycloakId) {
        try {
            keycloakAdminService.assignRealmRole(keycloakId, "PATIENT");
        } catch (Exception e) {
            log.warn("Could not assign PATIENT role in Keycloak: {}", e.getMessage());
        }
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
        insuranceService.recordPayment(id, request, authentication);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
