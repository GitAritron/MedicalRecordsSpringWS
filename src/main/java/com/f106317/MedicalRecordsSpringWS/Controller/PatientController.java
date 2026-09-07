package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.AccountCredentialsResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.InsurancePaymentRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientSelfRegistrationRequest;
import com.f106317.MedicalRecordsSpringWS.Service.AccountProvisioningService;
import com.f106317.MedicalRecordsSpringWS.Service.InsuranceService;
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
 * <p>
 * The Keycloak calls live here rather than in {@link PatientService} because they are writes to a
 * second system that no database transaction can undo — see {@link AccountProvisioningService}.
 */
@RestController
@RequestMapping("/api/patients")
@RequiredArgsConstructor
@Slf4j
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

    /**
     * Creates a patient record, optionally provisioning a Keycloak account for them.
     * WHY the ordering: the account is created before the record, so that a Keycloak failure stops
     * the write rather than reporting an account that does not exist. If the record save then
     * fails, the stranded account holds no role — the grant happens afterwards — so it is inert.
     */
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
        accountProvisioning.grantRoleQuietly(created.keycloakId(), PATIENT_ROLE);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    /**
     * Updates a patient, and keeps the Keycloak side in step.
     * WHY the revoke: when the record is pointed at a different account — or unlinked entirely by
     * clearing the field — the previous holder keeps the PATIENT role unless it is taken back.
     */
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

    /**
     * Issues a fresh one-time password for the account behind this record.
     * WHY: the password from provisioning is shown once and never stored, so without this an
     * administrator who lost it before passing it on had to fall back to the Keycloak console.
     */
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

    /**
     * Deletes a patient record and takes back the PATIENT role.
     * WHY the account itself survives: whether to delete, disable or merely de-role a person's
     * identity is identity-provider policy. Removing the role is enough to stop the stale account
     * reaching clinical data, without this application destroying an identity it does not own.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePatient(@PathVariable Long id, Authentication authentication) {
        String keycloakId = patientService.getPatientById(id, authentication).keycloakId();
        patientService.deletePatient(id, authentication);
        accountProvisioning.revokeRoleQuietly(keycloakId, PATIENT_ROLE);
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

    /** Attaches the one-time password to the response, but only when an account was just created. */
    private PatientResponse withPassword(PatientResponse response,
                                         AccountProvisioningService.ProvisionedAccount account) {
        return account.created() ? response.withTemporaryPassword(account.temporaryPassword()) : response;
    }
}
