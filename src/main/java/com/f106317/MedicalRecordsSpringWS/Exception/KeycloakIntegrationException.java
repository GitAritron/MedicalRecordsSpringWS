package com.f106317.MedicalRecordsSpringWS.Exception;

/**
 * Raised when a call to Keycloak's admin API cannot be completed — it is unreachable, rejected the
 * admin token request, or answered with something this application cannot use.
 * WHY its own type: these were bare {@code RuntimeException}s, so they reached the catch-all
 * handler and were reported as 500 "An unexpected error occurred". That is the wrong story on two
 * counts — the fault is in an upstream dependency rather than in this service, and the caller was
 * told nothing they could act on. As a distinct type it maps to 502, which says exactly that.
 *
 * <p>
 * It deliberately does NOT cover {@link UsernameAlreadyExistsException}: a taken username is a
 * successful conversation with a healthy Keycloak that the administrator can resolve themselves,
 * so it stays a 409.
 */
public class KeycloakIntegrationException extends RuntimeException {
    public KeycloakIntegrationException(String message) {
        super(message);
    }

    public KeycloakIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
