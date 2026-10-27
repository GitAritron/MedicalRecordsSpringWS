package com.f106317.MedicalRecordsSpringWS.Exception;

// The request is valid but the current data forbids it, e.g. deleting a doctor who has visits. Maps to 409.
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
