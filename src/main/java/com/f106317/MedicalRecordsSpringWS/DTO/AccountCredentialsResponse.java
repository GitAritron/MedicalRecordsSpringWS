package com.f106317.MedicalRecordsSpringWS.DTO;

// Returned after a password re-issue. Username is from Keycloak, not the medical record.
public record AccountCredentialsResponse(String username, String temporaryPassword) {}
