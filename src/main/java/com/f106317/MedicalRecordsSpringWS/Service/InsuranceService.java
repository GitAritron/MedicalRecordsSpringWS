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

    /** The specification asks whether contributions were paid over the last 6 months. */
    static final int REQUIRED_MONTHS = 6;

    /**
     * Whether the patient's contributions run unbroken across the last six months, reported rather
     * than reduced to a flag.
     * WHY: "not insured" alone does not say whether a patient missed one month or has never paid,
     * which is the difference between a reminder and a refusal.
     */
    @Transactional(readOnly = true)
    public InsuranceStatusResponse getStatus(Long patientId) {
        LocalDate today = LocalDate.now();
        Set<Integer> months = new HashSet<>(
                insuranceRepository.findCoveredMonthKeys(patientId, windowStart(today), today));
        boolean insured = consecutiveMonths(months, today) >= REQUIRED_MONTHS;
        return new InsuranceStatusResponse(insured, unpaidMonths(months, today));
    }

    /**
     * The unbroken run of paid months, ending with the current month or the one before it.
     * <p>
     * WHY consecutive rather than a plain count: the two agree on the verdict, but they disagree
     * about everyone else, and that is the number a person reads. A patient who paid five months and
     * then stopped is not insured; one who missed only the oldest month very nearly is. A count of
     * five describes both. The run separates them.
     * <p>
     * WHY the run may end last month: the current month is not yet over. Treating it as a missed
     * payment on the 1st would report everyone as lapsed for as long as it takes them to pay, which
     * is not what the contributions are — like any monthly bill, the month is owed by the time it
     * ends, not the moment it starts. So the run is anchored at the current month when it has been
     * paid, and at the previous month when it has not; a patient covered unbroken through last month
     * counts as insured, and paying this month simply keeps the run going.
     * <p>
     * Capped at {@code REQUIRED_MONTHS}: the rule asks about the last six months, not a lifetime.
     */
    private long consecutiveMonths(Set<Integer> coveredMonthKeys, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        YearMonth anchor = coveredMonthKeys.contains(key(current)) ? current : current.minusMonths(1);

        long run = 0;
        YearMonth month = anchor;
        while (run < REQUIRED_MONTHS && coveredMonthKeys.contains(key(month))) {
            run++;
            month = month.minusMonths(1);
        }
        return run;
    }

    /**
     * The months in the checked window carrying no contribution, oldest first.
     * <p>
     * WHY the window here ends with the *current* month rather than the run's anchor: this list
     * answers "what do I still owe", and the current month is owed even while the grace period
     * keeps the patient insured. A patient covered unbroken through last month therefore reads as
     * insured and still sees this month listed — which is exactly the position they are in.
     */
    private List<ReportDTO.UnpaidMonth> unpaidMonths(Set<Integer> coveredMonthKeys, LocalDate today) {
        List<ReportDTO.UnpaidMonth> unpaid = new ArrayList<>();
        YearMonth month = YearMonth.from(today).minusMonths(REQUIRED_MONTHS - 1L);
        for (int i = 0; i < REQUIRED_MONTHS; i++) {
            if (!coveredMonthKeys.contains(key(month))) {
                unpaid.add(new ReportDTO.UnpaidMonth(month.getYear(), month.getMonthValue()));
            }
            month = month.plusMonths(1);
        }
        return unpaid;
    }

    /** Sortable month identity, matching what the repository projects. */
    private static int key(YearMonth month) {
        return month.getYear() * 100 + month.getMonthValue();
    }

    /**
     * Coverage for the whole roster, in two queries rather than one per patient.
     * Patients who have never paid are absent from the repository result, so they are folded back
     * in here at zero — a report of who is covered has to name the people who are not.
     */
    @Transactional(readOnly = true)
    public List<ReportDTO.PatientInsuranceStatus> getStatusForAllPatients() {
        LocalDate today = LocalDate.now();
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

    /**
     * The first day of the oldest month the run could possibly reach.
     * <p>
     * Six months back rather than five, because a run anchored at *last* month can extend six months
     * from there. That fetches seven distinct months, which used to be the bug — the original code
     * counted payments inside a seven-month window while requiring only six, so a patient could skip
     * a month and still pass. It is safe now because nothing is counted: the run must be unbroken
     * from its anchor, so a gap ends it wherever the window happens to reach.
     */
    private LocalDate windowStart(LocalDate today) {
        return today.minusMonths(REQUIRED_MONTHS).withDayOfMonth(1);
    }

    /**
     * Records a health insurance payment for a patient.
     * The patient is passed separately because it comes from the request path, not the body.
     */
    @Transactional
    public void recordPayment(Long patientId, InsurancePaymentRequest request, Authentication authentication) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + patientId));
        // Insurance is bought by the month, so the covered month is the identity of the record.
        // The request carries a full date because the form is a date picker, and an administrator
        // recording February's contribution may pick any day in February. Normalising to the first
        // makes uq_patient_insurance_date mean "one payment per covered month", which is the rule
        // the column was always for, and matches the convention the seed data already uses.
        // paidOn is left exactly as sent - it is when the money actually arrived, and paying late
        // still covers the right month.
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
}
