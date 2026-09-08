package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationServiceTest {

    private AuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        authorizationService = new AuthorizationService();
    }

    private JwtAuthenticationToken createToken(String sub, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(sub)
                .claim("preferred_username", "testuser")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        var authorities = java.util.Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                .toList();
        return new JwtAuthenticationToken(jwt, authorities);
    }

    @Test
    void assertPatientCanAccessPatient_nullAuth_throws() {
        Patient patient = new Patient();
        patient.setKeycloakId("p1");
        assertThrows(AccessDeniedException.class,
                () -> authorizationService.assertPatientCanAccessPatient(null, patient));
    }

    @Test
    void assertPatientCanAccessPatient_admin_allowed() {
        Patient patient = new Patient();
        patient.setKeycloakId("p1");
        assertDoesNotThrow(
                () -> authorizationService.assertPatientCanAccessPatient(createToken("admin-sub", "ADMIN"), patient));
    }

    @Test
    void assertPatientCanAccessPatient_doctor_allowed() {
        Patient patient = new Patient();
        patient.setKeycloakId("p1");
        assertDoesNotThrow(
                () -> authorizationService.assertPatientCanAccessPatient(createToken("doc-sub", "DOCTOR"), patient));
    }

    @Test
    void assertPatientCanAccessPatient_ownRecord_allowed() {
        Patient patient = new Patient();
        patient.setKeycloakId("p1");
        assertDoesNotThrow(
                () -> authorizationService.assertPatientCanAccessPatient(createToken("p1", "PATIENT"), patient));
    }

    @Test
    void assertPatientCanAccessPatient_otherRecord_denied() {
        Patient patient = new Patient();
        patient.setKeycloakId("p1");
        assertThrows(AccessDeniedException.class,
                () -> authorizationService.assertPatientCanAccessPatient(createToken("p2", "PATIENT"), patient));
    }

    @Test
    void assertDoctorCanCreateVisit_nullAuth_throws() {
        Doctor doctor = new Doctor();
        doctor.setKeycloakId("d1");
        assertThrows(AccessDeniedException.class,
                () -> authorizationService.assertDoctorCanCreateVisit(null, doctor));
    }

    @Test
    void assertDoctorCanCreateVisit_ownDoctor_allowed() {
        Doctor doctor = new Doctor();
        doctor.setKeycloakId("d1");
        assertDoesNotThrow(
                () -> authorizationService.assertDoctorCanCreateVisit(createToken("d1", "DOCTOR"), doctor));
    }

    @Test
    void assertDoctorCanCreateVisit_otherDoctor_denied() {
        Doctor doctor = new Doctor();
        doctor.setKeycloakId("d1");
        assertThrows(AccessDeniedException.class,
                () -> authorizationService.assertDoctorCanCreateVisit(createToken("d2", "DOCTOR"), doctor));
    }

    @Test
    void isAdmin_true() {
        assertTrue(authorizationService.isAdmin(createToken("sub", "ADMIN")));
    }

    @Test
    void isAdmin_false() {
        assertFalse(authorizationService.isAdmin(createToken("sub", "DOCTOR")));
    }

    @Test
    void extractSubject_null_returnsNull() {
        assertNull(authorizationService.extractSubject(null));
    }

    @Test
    void extractSubject_jwt_returnsSub() {
        assertEquals("my-sub", authorizationService.extractSubject(createToken("my-sub", "PATIENT")));
    }
}
