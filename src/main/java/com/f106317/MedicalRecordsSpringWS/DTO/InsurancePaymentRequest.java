package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import java.time.LocalDate;

// No patientId - comes from the URL path. Dates must be past/present to prevent faking coverage.
public record InsurancePaymentRequest(
        @NotNull(message = "Insurance date is required")
        @PastOrPresent(message = "Insurance date cannot be in the future")
        LocalDate insuranceDate,
        @NotNull(message = "Payment date is required")
        @PastOrPresent(message = "Payment date cannot be in the future")
        LocalDate paidOn
) {}
