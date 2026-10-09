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

// Manages doctors, specializations, and Keycloak account provisioning.
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
    public ResponseEntity<SpecializationResponse> createSpecialization(@Valid @RequestBody SpecializationRequest request,
                                                                       Authentication authentication) {
        return new ResponseEntity<>(doctorService.createSpecialization(request, authentication), HttpStatus.CREATED);
    }

    @PutMapping("/specializations/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SpecializationResponse> updateSpecialization(@PathVariable Long id,
                                                                       @Valid @RequestBody SpecializationRequest request,
                                                                       Authentication authentication) {
        return ResponseEntity.ok(doctorService.updateSpecialization(id, request, authentication));
    }

    @DeleteMapping("/specializations/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteSpecialization(@PathVariable Long id, Authentication authentication) {
        doctorService.deleteSpecialization(id, authentication);
        return ResponseEntity.noContent().build();
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

    // Report: patients registered under a specific GP.
    @GetMapping("/{id}/patients")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<List<PatientResponse>> getPatientsByDoctor(@PathVariable Long id) {
        return ResponseEntity.ok(patientService.getPatientsByGp(id));
    }

    // Optionally creates a Keycloak account and grants DOCTOR role.
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

    // Self-registration: identity comes from the JWT, not the request body.
    @PostMapping("/self-register")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DoctorResponse> selfRegisterDoctor(@Valid @RequestBody DoctorSelfRegistrationRequest request,
                                                             Authentication authentication) {
        return new ResponseEntity<>(doctorService.selfRegister(request, authentication), HttpStatus.CREATED);
    }

    // Grants the DOCTOR role in Keycloak and records approval in the audit log.
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DoctorResponse> approveDoctor(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(doctorService.approveDoctor(id, authentication));
    }

    // On account re-link, revokes the old holder's DOCTOR role.
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

    @PostMapping("/{id}/reissue-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AccountCredentialsResponse> reissuePassword(@PathVariable Long id) {
        DoctorResponse doctor = doctorService.getDoctorById(id);
        return ResponseEntity.ok(accountProvisioning.reissuePassword(doctor.keycloakId()));
    }

    // Revokes the DOCTOR role but keeps the Keycloak account itself.
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteDoctor(@PathVariable Long id, Authentication authentication) {
        String keycloakId = doctorService.getDoctorById(id).keycloakId();
        doctorService.deleteDoctor(id, authentication);
        accountProvisioning.revokeRoleQuietly(keycloakId, DOCTOR_ROLE);
        return ResponseEntity.noContent().build();
    }

    private DoctorResponse withPassword(DoctorResponse response,
                                        AccountProvisioningService.ProvisionedAccount account) {
        return account.created() ? response.withTemporaryPassword(account.temporaryPassword()) : response;
    }
}
