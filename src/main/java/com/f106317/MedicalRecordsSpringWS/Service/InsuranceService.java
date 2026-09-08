package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.PaidInsurance;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PaidInsuranceRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PatientRepository;
import com.f106317.MedicalRecordsSpringWS.DTO.InsurancePaymentRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.InsuranceStatusResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        return getStatus(patientId).insured();
    }

    /**
     * The same check, reported rather than reduced to a flag.
     * WHY: "not insured" alone does not say whether a patient missed one month or has never paid,
     * which is the difference between a reminder and a refusal.
     */
    @Transactional(readOnly = true)
    public InsuranceStatusResponse getStatus(Long patientId) {
        LocalDate today = LocalDate.now();
        long covered = insuranceRepository.countCoveredMonths(patientId, windowStart(today), today);
        return new InsuranceStatusResponse(covered >= REQUIRED_MONTHS, covered, REQUIRED_MONTHS);
    }

    /**
     * Coverage for the whole roster, in two queries rather than one per patient.
     * Patients who have never paid are absent from the repository result, so they are folded back
     * in here at zero — a report of who is covered has to name the people who are not.
     */
    @Transactional(readOnly = true)
    public List<ReportDTO.PatientInsuranceStatus> getStatusForAllPatients() {
        LocalDate today = LocalDate.now();
        Map<Long, Long> coveredByPatient = insuranceRepository
                .countCoveredMonthsForAllPatients(windowStart(today), today).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        return patientRepository.findAll().stream()
                .map(patient -> {
                    long covered = coveredByPatient.getOrDefault(patient.getId(), 0L);
                    return new ReportDTO.PatientInsuranceStatus(
                            patient.getId(),
                            patient.getFirstName() + " " + patient.getLastName(),
                            patient.getEgn(),
                            covered,
                            REQUIRED_MONTHS,
                            covered >= REQUIRED_MONTHS);
                })
                .toList();
    }

    /**
     * The first day of the oldest month still inside the window.
     * The window includes the current month, so it starts {@code REQUIRED_MONTHS - 1} months back,
     * not a full six — going back six would span seven distinct months and let a patient miss one
     * and still pass.
     */
    private LocalDate windowStart(LocalDate today) {
        return today.minusMonths(REQUIRED_MONTHS - 1L).withDayOfMonth(1);
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
        auditLogService.log(authentication, "RECORD_INSURANCE", "PaidInsurance", saved.getId(),
                patient.getFirstName() + " " + patient.getLastName() + " - " + request.insuranceDate());
    }
}
