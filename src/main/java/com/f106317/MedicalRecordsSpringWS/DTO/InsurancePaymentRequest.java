package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import java.time.LocalDate;

/**
 * Records a health insurance contribution.
 * WHY no patientId: the patient is identified by the path of POST /api/patients/{id}/insurance.
 * Carrying it in the body as well meant the two could disagree, so the controller had to
 * hand-check them and reject the mismatch — a check that only existed because of the duplication.
 * <p>
 * WHY the date bounds: the coverage check counts months inside a window ending today, so a
 * future-dated contribution could otherwise be used to fake continuous coverage.
 */
public record InsurancePaymentRequest(
        @NotNull(message = "Insurance date is required")
        @PastOrPresent(message = "Insurance date cannot be in the future")
        LocalDate insuranceDate,
        @NotNull(message = "Payment date is required")
        @PastOrPresent(message = "Payment date cannot be in the future")
        LocalDate paidOn
) {}
