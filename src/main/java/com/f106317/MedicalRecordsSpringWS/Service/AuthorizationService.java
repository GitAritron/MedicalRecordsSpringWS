package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Visit;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    public static final String ROLE_PATIENT = "ROLE_PATIENT";

    public boolean isAdmin(Authentication authentication) {
        return hasRole(authentication, ROLE_ADMIN);
    }

    public boolean isDoctor(Authentication authentication) {
        return hasRole(authentication, ROLE_DOCTOR);
    }

    public boolean isPatient(Authentication authentication) {
        return hasRole(authentication, ROLE_PATIENT);
    }

    public String extractSubject(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken().getSubject();
        }
        return authentication.getName();
    }

    public void assertPatientCanAccessPatient(Authentication authentication, Patient patient) {
        requireAuthentication(authentication);
        if (isAdmin(authentication) || isDoctor(authentication)) {
            return;
        }
        if (!isPatient(authentication)) {
            throw new AccessDeniedException("Access denied");
        }
        String subject = extractSubject(authentication);
        if (subject == null || !subject.equals(patient.getKeycloakId())) {
            throw new AccessDeniedException("Patients can access only their own records");
        }
    }

    // GP change is patient + admin only. Doctors are excluded - it's an administrative decision, not clinical.
    public void assertCanChangeGeneralPractitioner(Authentication authentication, Patient patient) {
        requireAuthentication(authentication);
        if (isAdmin(authentication)) {
            return;
        }
        if (!isPatient(authentication)) {
            throw new AccessDeniedException("Access denied");
        }
        String subject = extractSubject(authentication);
        if (subject == null || !subject.equals(patient.getKeycloakId())) {
            throw new AccessDeniedException("Patients can change only their own general practitioner");
        }
    }

    public void assertPatientCanAccessVisit(Authentication authentication, Visit visit) {
        requireAuthentication(authentication);
        if (isAdmin(authentication) || isDoctor(authentication)) {
            return;
        }
        if (!isPatient(authentication)) {
            throw new AccessDeniedException("Access denied");
        }
        String subject = extractSubject(authentication);
        if (subject == null || !subject.equals(visit.getPatient().getKeycloakId())) {
            throw new AccessDeniedException("Patients can access only their own visits");
        }
    }

    public void assertDoctorCanCreateVisit(Authentication authentication, Doctor doctor) {
        requireAuthentication(authentication);
        if (isAdmin(authentication)) {
            return;
        }
        if (!isDoctor(authentication)) {
            throw new AccessDeniedException("Access denied");
        }
        String subject = extractSubject(authentication);
        if (subject == null || !subject.equals(doctor.getKeycloakId())) {
            throw new AccessDeniedException("Doctors can create visits only for themselves");
        }
    }

    public void assertDoctorCanModifyVisit(Authentication authentication, Visit visit) {
        requireAuthentication(authentication);
        if (isAdmin(authentication)) {
            return;
        }
        if (!isDoctor(authentication)) {
            throw new AccessDeniedException("Access denied");
        }
        String subject = extractSubject(authentication);
        if (subject == null || !subject.equals(visit.getDoctor().getKeycloakId())) {
            throw new AccessDeniedException("Doctors can modify only visits they have performed");
        }
    }

    private void requireAuthentication(Authentication authentication) {
        if (authentication == null) {
            throw new AccessDeniedException("Authentication is required");
        }
    }

    private boolean hasRole(Authentication authentication, String role) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> role.equals(grantedAuthority.getAuthority()));
    }
}
