package com.f106317.MedicalRecordsSpringWS.DTO;

import java.time.LocalDateTime;

/**
 * A doctor who has registered themselves and is waiting for an administrator to approve them.
 * WHY a dedicated shape: the previous screen listed raw audit rows, so an administrator saw an
 * entity id and had to look up who it referred to before deciding whether to approve.
 */
public record PendingDoctorResponse(
        Long doctorId,
        String doctorName,
        String keycloakId,
        LocalDateTime registeredAt
) {}
