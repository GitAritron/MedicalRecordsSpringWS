package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.AccountCredentialsResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorSelfRegistrationRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.SpecializationRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.SpecializationResponse;
import com.f106317.MedicalRecordsSpringWS.Service.AccountProvisioningService;
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
 * <p>
 * The Keycloak calls live here rather than in {@link DoctorService} because they are writes to a
 * second system that no database transaction can undo — see {@link AccountProvisioningService}.
 */
@RestController
@RequestMapping("/api/doctors")
@RequiredArgsConstructor
public class DoctorController {

    private static final String DOCTOR_ROLE = "DOCTOR";

    private final DoctorService doctorService;
    private final PatientService patientService;
    private final AccountProvisioningService accountProvisioning;

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

    /**
     * Creates a doctor record, optionally provisioning a Keycloak account for them.
     * WHY the role is granted here: previously nothing on this path touched Keycloak at all, so an
     * administrator who linked a doctor to an account produced someone the system believed was a
     * doctor but who could never sign in as one — and who never appeared in the pending-approvals
     * list either, because that is built from self-registrations.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DoctorResponse> createDoctor(@Valid @RequestBody DoctorRequest request, Authentication authentication) {
        var account = accountProvisioning.provisionIfRequested(
                request.username(), request.firstName(), request.lastName(),
                null, request.keycloakId());

        DoctorRequest effective = account.created() ? request.withKeycloakId(account.keycloakId()) : request;
        DoctorResponse created = doctorService.createDoctor(effective, authentication);

        accountProvisioning.grantRoleQuietly(created.keycloakId(), DOCTOR_ROLE);
        return new ResponseEntity<>(withPassword(created, account), HttpStatus.CREATED);
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

    /**
     * Updates a doctor, and keeps the Keycloak side in step.
     * WHY the revoke: when the record is pointed at a different account — or unlinked entirely by
     * clearing the field — the previous holder keeps the DOCTOR role unless it is taken back, and a
     * stale DOCTOR role still reaches every patient, visit and report through the API.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DoctorResponse> updateDoctor(@PathVariable Long id, @Valid @RequestBody DoctorRequest request, Authentication authentication) {
        String previousKeycloakId = doctorService.getDoctorById(id).keycloakId();

        var account = accountProvisioning.provisionIfRequested(
                request.username(), request.firstName(), request.lastName(),
                previousKeycloakId, request.keycloakId());

        DoctorRequest effective = account.created() ? request.withKeycloakId(account.keycloakId()) : request;
        DoctorResponse updated = doctorService.updateDoctor(id, effective, authentication);

        if (previousKeycloakId != null && !previousKeycloakId.equals(updated.keycloakId())) {
            accountProvisioning.revokeRoleQuietly(previousKeycloakId, DOCTOR_ROLE);
        }
        accountProvisioning.grantRoleQuietly(updated.keycloakId(), DOCTOR_ROLE);

        return ResponseEntity.ok(withPassword(updated, account));
    }

    /**
     * Issues a fresh one-time password for the account behind this record.
     */
    @PostMapping("/{id}/reissue-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AccountCredentialsResponse> reissuePassword(@PathVariable Long id) {
        DoctorResponse doctor = doctorService.getDoctorById(id);
        return ResponseEntity.ok(accountProvisioning.reissuePassword(doctor.keycloakId()));
    }

    /**
     * Deletes a doctor record and takes back the DOCTOR role.
     * WHY the account itself survives: see the note on deleting a patient — de-roling is enough to
     * cut off clinical data without this application destroying an identity it does not own.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteDoctor(@PathVariable Long id, Authentication authentication) {
        String keycloakId = doctorService.getDoctorById(id).keycloakId();
        doctorService.deleteDoctor(id, authentication);
        accountProvisioning.revokeRoleQuietly(keycloakId, DOCTOR_ROLE);
        return ResponseEntity.noContent().build();
    }

    /** Attaches the one-time password to the response, but only when an account was just created. */
    private DoctorResponse withPassword(DoctorResponse response,
                                        AccountProvisioningService.ProvisionedAccount account) {
        return account.created() ? response.withTemporaryPassword(account.temporaryPassword()) : response;
    }
}
