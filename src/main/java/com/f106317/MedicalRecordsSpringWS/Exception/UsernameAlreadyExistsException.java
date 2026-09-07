package com.f106317.MedicalRecordsSpringWS.Exception;

/**
 * Raised when Keycloak refuses to create a user because the username is taken.
 * WHY its own type: this is the one {@code createUser} failure an administrator can act on, so it
 * has to reach them as a 409 with a usable message rather than being folded into the generic
 * "Keycloak call failed" RuntimeException that produces a 500.
 */
public class UsernameAlreadyExistsException extends RuntimeException {
    public UsernameAlreadyExistsException(String message) {
        super(message);
    }
}
