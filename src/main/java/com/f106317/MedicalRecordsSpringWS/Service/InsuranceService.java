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

    /**
     * Checks if a patient has continuous insurance coverage over the last 6 months.
     * Requires payments for ALL 6 months (not just one).
     */
    @Transactional(readOnly = true)
    public boolean hasContinuousInsurance(Long patientId) {
        LocalDate sixMonthsAgo = LocalDate.now().minusMonths(6).withDayOfMonth(1);
        return insuranceRepository.hasContinuousInsurance(patientId, sixMonthsAgo);
    }

    /**
     * Records a health insurance payment for a patient.
     */
    @Transactional
    public void recordPayment(InsurancePaymentRequest request, Authentication authentication) {
        Patient patient = patientRepository.findById(request.patientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + request.patientId()));
        PaidInsurance payment = new PaidInsurance();
        payment.setPatient(patient);
        payment.setInsuranceDate(request.insuranceDate());
        payment.setPaidOn(request.paidOn());
        PaidInsurance saved = insuranceRepository.save(payment);
        auditLogService.log(authentication, "RECORD_INSURANCE", "PaidInsurance", saved.getId());
    }
}
