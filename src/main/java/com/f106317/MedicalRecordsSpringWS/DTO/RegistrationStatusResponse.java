package com.f106317.MedicalRecordsSpringWS.DTO;

/**
 * What the signed-in user has already registered as, reported only about themselves.
 * WHY: a doctor waiting for approval holds no realm role, so every profile lookup refuses them.
 * Without this they return to the role-selection screen on each sign-in with no sign that their
 * application exists, and re-applying just hits the duplicate-profile guard.
 */
public record RegistrationStatusResponse(
        boolean hasDoctorProfile,
        boolean hasPatientProfile
) {}
