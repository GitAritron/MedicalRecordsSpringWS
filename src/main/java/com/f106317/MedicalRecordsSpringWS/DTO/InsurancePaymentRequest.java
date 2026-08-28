package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record InsurancePaymentRequest(
        @NotNull(message = "Patient ID is required")
        Long patientId,
        @NotNull(message = "Insurance date is required")
        LocalDate insuranceDate,
        @NotNull(message = "Payment date is required")
        LocalDate paidOn
) {}
