package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.AccountCredentialsResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.InsurancePaymentRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.InsurancePaymentResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.InsuranceStatusResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientSelfRegistrationRequest;
import com.f106317.MedicalRecordsSpringWS.Service.AccountProvisioningService;
import com.f106317.MedicalRecordsSpringWS.Service.InsuranceService;
import com.f106317.MedicalRecordsSpringWS.Service.PatientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Manages patients, insurance payments, and Keycloak account provisioning.
@RestController
@RequestMapping("/api/patients")
@RequiredArgsConstructor
public class PatientController {

    private static final String PATIENT_ROLE = "PATIENT";

    private final PatientService patientService;
    private final InsuranceService insuranceService;
    private final AccountProvisioningService accountProvisioning;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<List<PatientResponse>> getAllPatients() {
        return ResponseEntity.ok(patientService.getAllPatients());
    }

    // Resolves the caller's own profile from their JWT subject.
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

    // Account is created before the DB record so a Keycloak failure stops the whole write.
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PatientResponse> createPatient(@Valid @RequestBody PatientRequest request, Authentication authentication) {
        var account = accountProvisioning.provisionIfRequested(
                request.username(), request.firstName(), request.lastName(),
                null, request.keycloakId());

        PatientRequest effective = account.created() ? request.withKeycloakId(account.keycloakId()) : request;
        PatientResponse created = patientService.createPatient(effective, authentication);

        accountProvisioning.grantRoleQuietly(created.keycloakId(), PATIENT_ROLE);
        return new ResponseEntity<>(withPassword(created, account), HttpStatus.CREATED);
    }

    // Self-registration - binds to the JWT subject. Idempotent: returns 200 if already registered.
    @PostMapping("/self-register")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PatientResponse> selfRegisterPatient(@Valid @RequestBody PatientSelfRegistrationRequest request,
                                                               Authentication authentication) {
        PatientService.SelfRegistration outcome = patientService.selfRegister(request, authentication);
        accountProvisioning.grantRoleQuietly(outcome.patient().keycloakId(), PATIENT_ROLE);
        return new ResponseEntity<>(outcome.patient(),
                outcome.created() ? HttpStatus.CREATED : HttpStatus.OK);
    }

    // On account re-link, revokes the old holder's PATIENT role.
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PatientResponse> updatePatient(@PathVariable Long id, @Valid @RequestBody PatientRequest request, Authentication authentication) {
        String previousKeycloakId = patientService.getPatientById(id, authentication).keycloakId();

        var account = accountProvisioning.provisionIfRequested(
                request.username(), request.firstName(), request.lastName(),
                previousKeycloakId, request.keycloakId());

        PatientRequest effective = account.created() ? request.withKeycloakId(account.keycloakId()) : request;
        PatientResponse updated = patientService.updatePatient(id, effective, authentication);

        if (previousKeycloakId != null && !previousKeycloakId.equals(updated.keycloakId())) {
            accountProvisioning.revokeRoleQuietly(previousKeycloakId, PATIENT_ROLE);
        }
        accountProvisioning.grantRoleQuietly(updated.keycloakId(), PATIENT_ROLE);

        return ResponseEntity.ok(withPassword(updated, account));
    }

    @PostMapping("/{id}/reissue-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AccountCredentialsResponse> reissuePassword(@PathVariable Long id, Authentication authentication) {
        PatientResponse patient = patientService.getPatientById(id, authentication);
        return ResponseEntity.ok(accountProvisioning.reissuePassword(patient.keycloakId()));
    }

    @PatchMapping("/{id}/gp")
    @PreAuthorize("hasAnyRole('ADMIN', 'PATIENT')")
    public ResponseEntity<PatientResponse> updatePatientGp(@PathVariable Long id, @RequestParam Long gpDoctorId, Authentication authentication) {
        return ResponseEntity.ok(patientService.updatePatientGp(id, gpDoctorId, authentication));
    }

    // Revokes the PATIENT role but keeps the Keycloak account itself.
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePatient(@PathVariable Long id, Authentication authentication) {
        String keycloakId = patientService.getPatientById(id, authentication).keycloakId();
        patientService.deletePatient(id, authentication);
        accountProvisioning.revokeRoleQuietly(keycloakId, PATIENT_ROLE);
        return ResponseEntity.noContent().build();
    }

    // 6-month insurance status check.
    @GetMapping("/{id}/insurance")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'PATIENT')")
    public ResponseEntity<InsuranceStatusResponse> checkInsurance(@PathVariable Long id, Authentication authentication) {
        patientService.getPatientById(id, authentication);
        return ResponseEntity.ok(insuranceService.getStatus(id));
    }

    // Admin-only: doctors can read insurance status but not record payments.
    @PostMapping("/{id}/insurance")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> recordInsurancePayment(@PathVariable Long id, @Valid @RequestBody InsurancePaymentRequest request, Authentication authentication) {
        insuranceService.recordPayment(id, request, authentication);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // Admin-only: individual payment records for correction.
    @GetMapping("/{id}/insurance/payments")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<InsurancePaymentResponse>> listInsurancePayments(@PathVariable Long id) {
        return ResponseEntity.ok(insuranceService.listPayments(id));
    }

    @PutMapping("/{id}/insurance/payments/{paymentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InsurancePaymentResponse> updateInsurancePayment(
            @PathVariable Long id, @PathVariable Long paymentId,
            @Valid @RequestBody InsurancePaymentRequest request, Authentication authentication) {
        return ResponseEntity.ok(insuranceService.updatePayment(id, paymentId, request, authentication));
    }

    @DeleteMapping("/{id}/insurance/payments/{paymentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteInsurancePayment(@PathVariable Long id, @PathVariable Long paymentId,
                                                       Authentication authentication) {
        insuranceService.deletePayment(id, paymentId, authentication);
        return ResponseEntity.noContent().build();
    }

    private PatientResponse withPassword(PatientResponse response,
                                         AccountProvisioningService.ProvisionedAccount account) {
        return account.created() ? response.withTemporaryPassword(account.temporaryPassword()) : response;
    }
}
