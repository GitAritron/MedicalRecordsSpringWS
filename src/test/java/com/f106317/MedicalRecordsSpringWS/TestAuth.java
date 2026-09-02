package com.f106317.MedicalRecordsSpringWS;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the same kind of {@link Authentication} the running application sees — a
 * {@link JwtAuthenticationToken} carrying a subject and {@code ROLE_*} authorities, exactly as
 * {@code SecurityConfig.keycloakJwtConverter()} produces from a Keycloak token.
 * <p>
 * WHY: service tests used to mock {@code AuthorizationService} away, which made every ownership
 * assertion a no-op. Passing a realistic token instead lets the real authorization logic run, so
 * the rules in specification §2 ("patients see only their own records", "doctors edit only visits
 * they performed") are actually exercised.
 */
public final class TestAuth {

    public static final String ADMIN_SUBJECT = "admin-subject";

    private TestAuth() {
    }

    /** An administrator; passes every ownership check by role. */
    public static Authentication admin() {
        return of(ADMIN_SUBJECT, "ROLE_ADMIN");
    }

    /** A doctor identified by the Keycloak subject stored on their Doctor record. */
    public static Authentication doctor(String keycloakId) {
        return of(keycloakId, "ROLE_DOCTOR");
    }

    /** A patient identified by the Keycloak subject stored on their Patient record. */
    public static Authentication patient(String keycloakId) {
        return of(keycloakId, "ROLE_PATIENT");
    }

    /** An authenticated user holding no realm roles at all. */
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
