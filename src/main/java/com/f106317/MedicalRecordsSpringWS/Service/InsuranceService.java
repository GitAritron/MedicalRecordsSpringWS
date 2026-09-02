package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.PaidInsurance;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PaidInsuranceRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PatientRepository;
import com.f106317.MedicalRecordsSpringWS.DTO.InsurancePaymentRequest;
import com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class InsuranceService {

    private final PaidInsuranceRepository insuranceRepository;
    private final PatientRepository patientRepository;
    private final AuditLogService auditLogService;

    /** The specification asks whether contributions were paid over the last 6 months. */
    static final int REQUIRED_MONTHS = 6;

    /**
     * Checks if a patient has continuous insurance coverage over the last 6 months.
     * WHAT: Requires a payment in every one of the 6 calendar months ending with the current one.
     * WHY: The window includes the current month, so it starts 5 months back, not 6 — going back a
     * full 6 months would span 7 distinct months and let a patient miss one and still pass. The
     * window also ends today, so payments dated in the future cannot be used to satisfy the check.
     */
    @Transactional(readOnly = true)
    public boolean hasContinuousInsurance(Long patientId) {
        LocalDate today = LocalDate.now();
        LocalDate windowStart = today.minusMonths(REQUIRED_MONTHS - 1L).withDayOfMonth(1);
        return insuranceRepository.countCoveredMonths(patientId, windowStart, today) >= REQUIRED_MONTHS;
    }

    /**
     * Records a health insurance payment for a patient.
     * The patient is passed separately because it comes from the request path, not the body.
     */
    @Transactional
    public void recordPayment(Long patientId, InsurancePaymentRequest request, Authentication authentication) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + patientId));
        PaidInsurance payment = new PaidInsurance();
        payment.setPatient(patient);
        payment.setInsuranceDate(request.insuranceDate());
        payment.setPaidOn(request.paidOn());
        PaidInsurance saved = insuranceRepository.save(payment);
        auditLogService.log(authentication, "RECORD_INSURANCE", "PaidInsurance", saved.getId());
    }
}
