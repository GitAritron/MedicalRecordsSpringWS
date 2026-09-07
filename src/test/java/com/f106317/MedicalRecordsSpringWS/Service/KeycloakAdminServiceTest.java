package com.f106317.MedicalRecordsSpringWS.Service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void assignRealmRole_whenKeycloakUnreachable_throwsRuntimeException() {
        assertThrows(RuntimeException.class, () ->
            keycloakAdminService.assignRealmRole("user-123", "PATIENT")
        );
    }

    @Test
    void removeRealmRole_whenKeycloakUnreachable_throwsRuntimeException() {
        assertThrows(RuntimeException.class, () ->
            keycloakAdminService.removeRealmRole("user-123", "DOCTOR")
        );
    }

    @Test
    void createUser_whenKeycloakUnreachable_throwsRuntimeException() {
        assertThrows(RuntimeException.class, () ->
            keycloakAdminService.createUser("ipetrov", "Ivan", "Petrov", "Tmp12345678")
        );
    }

    @Test
    void getUsername_whenKeycloakUnreachable_throwsRuntimeException() {
        assertThrows(RuntimeException.class, () ->
            keycloakAdminService.getUsername("user-123")
        );
    }

    @Test
    void resetPassword_whenKeycloakUnreachable_throwsRuntimeException() {
        assertThrows(RuntimeException.class, () ->
            keycloakAdminService.resetPassword("user-123", "Tmp12345678")
        );
    }

    /**
     * The generated password has to satisfy the realm's policy without anyone tuning it, and must
     * not repeat between issues — two accounts created in the same session sharing a password would
     * be a quiet way to hand one person access to another's.
     */
    @Test
    void generateTemporaryPassword_isLongAndNotRepeated() {
        String first = keycloakAdminService.generateTemporaryPassword();
        String second = keycloakAdminService.generateTemporaryPassword();

        assertEquals(12, first.length());
        assertNotEquals(first, second);
    }
}
