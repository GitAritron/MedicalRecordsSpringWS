package com.f106317.MedicalRecordsSpringWS.DTO;

import java.time.LocalDateTime;

// Doctor awaiting admin approval. Shows name instead of raw audit entity ID.
public record PendingDoctorResponse(
        Long doctorId,
        String doctorName,
        String keycloakId,
        LocalDateTime registeredAt
) {}
