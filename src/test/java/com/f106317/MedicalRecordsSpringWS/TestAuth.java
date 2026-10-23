package com.f106317.MedicalRecordsSpringWS;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

// Real JwtAuthenticationTokens, so service tests run the actual ownership checks instead of a mocked AuthorizationService.
public final class TestAuth {

    public static final String ADMIN_SUBJECT = "admin-subject";

    private TestAuth() {
    }

    public static Authentication admin() {
        return of(ADMIN_SUBJECT, "ROLE_ADMIN");
    }

    public static Authentication doctor(String keycloakId) {
        return of(keycloakId, "ROLE_DOCTOR");
    }

    public static Authentication patient(String keycloakId) {
        return of(keycloakId, "ROLE_PATIENT");
    }

    public static Authentication roleless(String keycloakId) {
        return of(keycloakId);
    }

    public static Authentication of(String subject, String... authorities) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("preferred_username", subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a))
                .toList();
        return new JwtAuthenticationToken(jwt, granted, subject);
    }
}
