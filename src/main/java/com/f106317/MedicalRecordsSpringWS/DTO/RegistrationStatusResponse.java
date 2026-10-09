package com.f106317.MedicalRecordsSpringWS.DTO;

// Tells the caller which profiles they already have. Used to skip role-selection on repeat sign-in.
public record RegistrationStatusResponse(
        boolean hasDoctorProfile,
        boolean hasPatientProfile
) {}
