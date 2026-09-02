package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.RegistrationStatusResponse;
import com.f106317.MedicalRecordsSpringWS.Service.AuthorizationService;
import com.f106317.MedicalRecordsSpringWS.Service.DoctorService;
import com.f106317.MedicalRecordsSpringWS.Service.PatientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the signed-in user which profiles they already hold.
 * WHY it is safe for any authenticated caller: the subject is taken from the verified token, never
 * from a parameter, so a user can only ever ask about themselves. It returns two booleans and no
 * personal data.
 */
@RestController
@RequestMapping("/api/registration-status")
@RequiredArgsConstructor
public class RegistrationStatusController {

    private final DoctorService doctorService;
    private final PatientService patientService;
    private final AuthorizationService authorizationService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RegistrationStatusResponse> getMyRegistrationStatus(Authentication authentication) {
        String subject = authorizationService.extractSubject(authentication);
        return ResponseEntity.ok(new RegistrationStatusResponse(
                doctorService.hasProfileFor(subject),
                patientService.hasProfileFor(subject)));
    }
}
