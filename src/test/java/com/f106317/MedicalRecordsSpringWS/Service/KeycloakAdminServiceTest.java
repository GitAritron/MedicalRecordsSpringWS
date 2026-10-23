package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Exception.KeycloakIntegrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Nothing listens on :9999, so every call hits a real connection failure.
// It must surface as KeycloakIntegrationException (502), not a raw RestClientException (500).
class KeycloakAdminServiceTest {

    private KeycloakAdminService keycloakAdminService;

    @BeforeEach
    void setUp() {
        keycloakAdminService = new KeycloakAdminService();
        ReflectionTestUtils.setField(keycloakAdminService, "serverUrl", "http://localhost:9999");
        ReflectionTestUtils.setField(keycloakAdminService, "realm", "test-realm");
        ReflectionTestUtils.setField(keycloakAdminService, "adminUsername", "admin");
        ReflectionTestUtils.setField(keycloakAdminService, "adminPassword", "admin");
    }

    @Test
    void assignRealmRole_whenKeycloakUnreachable_throwsKeycloakIntegrationException() {
        assertThrows(KeycloakIntegrationException.class, () ->
            keycloakAdminService.assignRealmRole("user-123", "PATIENT")
        );
    }

    @Test
    void removeRealmRole_whenKeycloakUnreachable_throwsKeycloakIntegrationException() {
        assertThrows(KeycloakIntegrationException.class, () ->
            keycloakAdminService.removeRealmRole("user-123", "DOCTOR")
        );
    }

    @Test
    void createUser_whenKeycloakUnreachable_throwsKeycloakIntegrationException() {
        assertThrows(KeycloakIntegrationException.class, () ->
            keycloakAdminService.createUser("ipetrov", "Ivan", "Petrov", "Tmp12345678")
        );
    }

    @Test
    void getUsername_whenKeycloakUnreachable_throwsKeycloakIntegrationException() {
        assertThrows(KeycloakIntegrationException.class, () ->
            keycloakAdminService.getUsername("user-123")
        );
    }

    @Test
    void resetPassword_whenKeycloakUnreachable_throwsKeycloakIntegrationException() {
        assertThrows(KeycloakIntegrationException.class, () ->
            keycloakAdminService.resetPassword("user-123", "Tmp12345678")
        );
    }

    // Must meet the realm policy and never repeat - a shared password would give one person access to another's account.
    @Test
    void generateTemporaryPassword_isLongAndNotRepeated() {
        String first = keycloakAdminService.generateTemporaryPassword();
        String second = keycloakAdminService.generateTemporaryPassword();

        assertEquals(12, first.length());
        assertNotEquals(first, second);
    }
}
