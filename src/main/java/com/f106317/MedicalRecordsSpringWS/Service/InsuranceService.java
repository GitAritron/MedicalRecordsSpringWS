package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.PaidInsurance;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PaidInsuranceRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PatientRepository;
import com.f106317.MedicalRecordsSpringWS.DTO.InsurancePaymentRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.InsurancePaymentResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.InsuranceStatusResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class InsuranceService {

    private final PaidInsuranceRepository insuranceRepository;
    private final PatientRepository patientRepository;
    private final AuditLogService auditLogService;

    // 6 month insurance window per the assignment specification.
    static final int REQUIRED_MONTHS = 6;

    // Returns which months are unpaid, not just a boolean, so the user knows what to pay.
    @Transactional(readOnly = true)
    public InsuranceStatusResponse getStatus(Long patientId) {
        LocalDate today = LocalDate.now();
        Set<Integer> months = new HashSet<>(
                insuranceRepository.findCoveredMonthKeys(patientId, windowStart(today), today));
        boolean insured = consecutiveMonths(months, today) >= REQUIRED_MONTHS;
        return new InsuranceStatusResponse(insured, unpaidMonths(months, today));
    }

    // Counts the unbroken run of paid months ending at the current (or previous) month.
    // Current month is not required yet - like a monthly bill, it's owed by the end of the month.
    private long consecutiveMonths(Set<Integer> coveredMonthKeys, LocalDate today) {
        long run = 0;
        YearMonth month = anchor(coveredMonthKeys, YearMonth.from(today));
        while (run < REQUIRED_MONTHS && coveredMonthKeys.contains(key(month))) {
            run++;
            month = month.minusMonths(1);
        }
        return run;
    }

    // Specific months with no payment. Can be BOTH insured AND owing the current month.
    private List<ReportDTO.UnpaidMonth> unpaidMonths(Set<Integer> coveredMonthKeys, LocalDate today) {
        List<ReportDTO.UnpaidMonth> unpaid = new ArrayList<>();
        YearMonth current = YearMonth.from(today);
        // Starts where the verdict's window starts, so the month that breaks the run is always named.
        YearMonth month = anchor(coveredMonthKeys, current).minusMonths(REQUIRED_MONTHS - 1L);
        while (!month.isAfter(current)) {
            if (!coveredMonthKeys.contains(key(month))) {
                unpaid.add(new ReportDTO.UnpaidMonth(month.getYear(), month.getMonthValue()));
            }
            month = month.plusMonths(1);
        }
        return unpaid;
    }

    // The run ends at the current month if it is paid, otherwise at the previous one.
    private static YearMonth anchor(Set<Integer> coveredMonthKeys, YearMonth current) {
        return coveredMonthKeys.contains(key(current)) ? current : current.minusMonths(1);
    }

    // Sortable month identity (same as in repository)
    private static int key(YearMonth month) {
        return month.getYear() * 100 + month.getMonthValue();
    }

    // Two queries instead of one per patient. Patients who never paid are included with zero coverage.
    @Transactional(readOnly = true)
    public List<ReportDTO.PatientInsuranceStatus> getStatusForAllPatients() {
        LocalDate today = LocalDate.now();
        // Unpack the JPQL projection [patientId, yearMonthKey] into a lookup map per patient.
        Map<Long, Set<Integer>> monthsByPatient = new HashMap<>();
        for (Object[] row : insuranceRepository.findCoveredMonthKeysForAllPatients(windowStart(today), today)) {
            monthsByPatient
                    .computeIfAbsent((Long) row[0], id -> new HashSet<>())
                    .add(((Number) row[1]).intValue());
        }

        return patientRepository.findAll().stream()
                .map(patient -> {
                    Set<Integer> months = monthsByPatient.getOrDefault(patient.getId(), Set.of());
                    return new ReportDTO.PatientInsuranceStatus(
                            patient.getId(),
                            patient.getFirstName() + " " + patient.getLastName(),
                            patient.getEgn(),
                            consecutiveMonths(months, today) >= REQUIRED_MONTHS,
                            unpaidMonths(months, today));
                })
                .toList();
    }

    // Starts 6 months back to cover the full consecutive-run check.
    private LocalDate windowStart(LocalDate today) {
        return today.minusMonths(REQUIRED_MONTHS).withDayOfMonth(1);
    }

    @Transactional
    public void recordPayment(Long patientId, InsurancePaymentRequest request, Authentication authentication) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + patientId));
        // Normalise to 1st of the month - one payment per covered month.
        LocalDate coveredMonth = request.insuranceDate().withDayOfMonth(1);

        if (insuranceRepository.existsByPatientIdAndInsuranceDate(patientId, coveredMonth)) {
            throw new IllegalArgumentException("Insurance for "
                    + YearMonth.from(coveredMonth) + " is already recorded for this patient");
        }

        PaidInsurance payment = new PaidInsurance();
        payment.setPatient(patient);
        payment.setInsuranceDate(coveredMonth);
        payment.setPaidOn(request.paidOn());
        PaidInsurance saved = insuranceRepository.save(payment);
        auditLogService.log(authentication, "RECORD_INSURANCE", "PaidInsurance", saved.getId(),
                patient.getFirstName() + " " + patient.getLastName() + " - " + YearMonth.from(coveredMonth));
    }

    // Returns individual payments so wrong entries can be spotted and corrected.
    @Transactional(readOnly = true)
    public List<InsurancePaymentResponse> listPayments(Long patientId) {
        requirePatient(patientId);
        return insuranceRepository.findByPatientIdOrderByInsuranceDateDesc(patientId).stream()
                .map(InsurancePaymentResponse::fromEntity)
                .toList();
    }

    // Can't move a payment to a month that's already covered by another payment.
    @Transactional
    public InsurancePaymentResponse updatePayment(Long patientId, Long paymentId,
                                                  InsurancePaymentRequest request,
                                                  Authentication authentication) {
        Patient patient = requirePatient(patientId);
        PaidInsurance payment = requirePayment(patientId, paymentId);
        LocalDate coveredMonth = request.insuranceDate().withDayOfMonth(1);

        insuranceRepository.findByPatientIdAndInsuranceDate(patientId, coveredMonth)
                .filter(other -> !other.getId().equals(paymentId))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("Insurance for "
                            + YearMonth.from(coveredMonth) + " is already recorded for this patient");
                });

        payment.setInsuranceDate(coveredMonth);
        payment.setPaidOn(request.paidOn());
        PaidInsurance saved = insuranceRepository.save(payment);
        auditLogService.log(authentication, "UPDATE", "PaidInsurance", saved.getId(),
                patient.getFirstName() + " " + patient.getLastName() + " - " + YearMonth.from(coveredMonth));
        return InsurancePaymentResponse.fromEntity(saved);
    }

    @Transactional
    public void deletePayment(Long patientId, Long paymentId, Authentication authentication) {
        Patient patient = requirePatient(patientId);
        PaidInsurance payment = requirePayment(patientId, paymentId);
        YearMonth coveredMonth = YearMonth.from(payment.getInsuranceDate());
        insuranceRepository.delete(payment);
        auditLogService.log(authentication, "DELETE", "PaidInsurance", paymentId,
                patient.getFirstName() + " " + patient.getLastName() + " - " + coveredMonth);
    }

    private Patient requirePatient(Long patientId) {
        return patientRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + patientId));
    }

    // Ensures the payment belongs to the patient in the URL path.
    private PaidInsurance requirePayment(Long patientId, Long paymentId) {
        PaidInsurance payment = insuranceRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Insurance payment not found with id: " + paymentId));
        if (payment.getPatient() == null || !payment.getPatient().getId().equals(patientId)) {
            throw new ResourceNotFoundException(
                    "Insurance payment " + paymentId + " does not belong to patient " + patientId);
        }
        return payment;
    }
}
