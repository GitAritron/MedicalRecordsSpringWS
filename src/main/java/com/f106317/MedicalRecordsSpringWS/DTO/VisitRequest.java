package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record VisitRequest(
        @NotNull(message = "Patient ID is required")
        Long patientId,
        @NotNull(message = "Doctor ID is required")
        Long doctorId,
        @NotNull(message = "Visit date is required")
        LocalDate visitDate,
        @NotNull(message = "Visit time is required")
        LocalTime visitTime
) {}
