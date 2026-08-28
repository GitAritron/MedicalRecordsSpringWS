package com.f106317.MedicalRecordsSpringWS.Service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
}
