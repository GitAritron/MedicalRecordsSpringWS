package com.f106317.MedicalRecordsSpringWS.Exception;

// Keycloak is unreachable or returned an unusable response. Maps to 502.
public class KeycloakIntegrationException extends RuntimeException {
    public KeycloakIntegrationException(String message) {
        super(message);
    }

    public KeycloakIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
