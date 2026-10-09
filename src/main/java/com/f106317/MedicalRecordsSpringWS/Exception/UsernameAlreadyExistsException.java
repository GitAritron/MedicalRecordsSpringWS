package com.f106317.MedicalRecordsSpringWS.Exception;

// Keycloak username taken. Maps to 409 so the admin can pick a different one.
public class UsernameAlreadyExistsException extends RuntimeException {
    public UsernameAlreadyExistsException(String message) {
        super(message);
    }
}
