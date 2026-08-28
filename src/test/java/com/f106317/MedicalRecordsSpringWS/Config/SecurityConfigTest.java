package com.f106317.MedicalRecordsSpringWS.Config;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void testKeycloakJwtConverterWithRoles() {
        Converter<Jwt, AbstractAuthenticationToken> converter = securityConfig.keycloakJwtConverter();
        
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("preferred_username", "testuser")
                .claim("realm_access", Map.of("roles", List.of("ADMIN", "DOCTOR")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
                
        AbstractAuthenticationToken token = converter.convert(jwt);
        
        assertEquals("testuser", token.getName());
        Collection<GrantedAuthority> authorities = token.getAuthorities();
        assertEquals(2, authorities.size());
        assertTrue(authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        assertTrue(authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_DOCTOR")));
    }
    
    @Test
    void testKeycloakJwtConverterWithoutRoles() {
        Converter<Jwt, AbstractAuthenticationToken> converter = securityConfig.keycloakJwtConverter();
        
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("preferred_username", "testuser")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
                
        AbstractAuthenticationToken token = converter.convert(jwt);
        
        assertEquals("testuser", token.getName());
        assertTrue(token.getAuthorities().isEmpty());
    }
    
    @Test
    void testKeycloakJwtConverterWithEmptyRealmAccess() {
        Converter<Jwt, AbstractAuthenticationToken> converter = securityConfig.keycloakJwtConverter();
        
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("preferred_username", "testuser")
                .claim("realm_access", Map.of())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
                
        AbstractAuthenticationToken token = converter.convert(jwt);
        
        assertEquals("testuser", token.getName());
        assertTrue(token.getAuthorities().isEmpty());
    }
}
