package com.f106317.MedicalRecordsSpringWS.DTO;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.PaidInsurance;

import java.time.LocalDate;

// insuranceDate = covered month, paidOn = when money arrived. Both shown so wrong entries can be identified.
public record InsurancePaymentResponse(
        Long id,
        Long patientId,
        LocalDate insuranceDate,
        LocalDate paidOn
) {
    public static InsurancePaymentResponse fromEntity(PaidInsurance payment) {
        return new InsurancePaymentResponse(
                payment.getId(),
                payment.getPatient() != null ? payment.getPatient().getId() : null,
                payment.getInsuranceDate(),
                payment.getPaidOn());
    }
}
