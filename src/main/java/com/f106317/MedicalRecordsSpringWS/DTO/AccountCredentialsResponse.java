package com.f106317.MedicalRecordsSpringWS.DTO;

/**
 * Sign-in details for an administrator to hand over after a password is re-issued.
 * <p>
 * WHY its own shape rather than a field on {@link PatientResponse} or {@link DoctorResponse}:
 * re-issuing a password changes nothing about the medical record, so returning one would be
 * describing something that did not happen. The username also belongs to Keycloak rather than to
 * the record, and is read back from there — the person signs in with it, so naming them instead
 * would send an administrator off to hand over a value that does not work.
 */
public record AccountCredentialsResponse(String username, String temporaryPassword) {}
