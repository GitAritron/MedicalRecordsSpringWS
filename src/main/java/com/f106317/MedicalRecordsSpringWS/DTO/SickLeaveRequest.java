package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record SickLeaveRequest(
        @NotNull(message = "Visit ID is required")
        Long visitId,
        @NotNull(message = "Valid from date is required")
        LocalDate validFrom,
        @Min(value = 1, message = "Valid days must be at least 1")
        @Max(value = 365, message = "Valid days cannot exceed 365")
        int validForDays
) {}
