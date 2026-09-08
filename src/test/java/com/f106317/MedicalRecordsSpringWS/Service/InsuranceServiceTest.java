package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.TestAuth;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.PaidInsurance;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PaidInsuranceRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PatientRepository;
import com.f106317.MedicalRecordsSpringWS.DTO.InsurancePaymentRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.InsuranceStatusResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsuranceServiceTest {

    @Mock
    private PaidInsuranceRepository insuranceRepository;

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private InsuranceService insuranceService;

    /** All 6 months of the window are covered, so the patient counts as insured. */
    @Test
    void hasContinuousInsurance_true() {
        when(insuranceRepository.countCoveredMonths(eq(1L), any(), any())).thenReturn(6L);
        assertTrue(insuranceService.hasContinuousInsurance(1L));
    }

    /**
     * Five covered months must fail.
     * WHY: this is the regression guard for the original off-by-one — the old window spanned seven
     * calendar months while requiring only six, so a patient could skip a month and still pass.
     */
    @Test
    void hasContinuousInsurance_falseWhenOneMonthMissing() {
        when(insuranceRepository.countCoveredMonths(eq(1L), any(), any())).thenReturn(5L);
        assertFalse(insuranceService.hasContinuousInsurance(1L));
    }

    @Test
    void hasContinuousInsurance_falseWhenNoPayments() {
        when(insuranceRepository.countCoveredMonths(eq(1L), any(), any())).thenReturn(0L);
        assertFalse(insuranceService.hasContinuousInsurance(1L));
    }

    /**
     * Pins down the window itself: it must span exactly 6 calendar months, start on the 1st, and
     * end today — so that neither a 7th month nor a future-dated payment can be counted.
     */
    @Test
    void hasContinuousInsurance_windowSpansExactlySixMonthsEndingToday() {
        when(insuranceRepository.countCoveredMonths(eq(1L), any(), any())).thenReturn(6L);

        insuranceService.hasContinuousInsurance(1L);

        ArgumentCaptor<LocalDate> start = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> end = ArgumentCaptor.forClass(LocalDate.class);
        verify(insuranceRepository).countCoveredMonths(eq(1L), start.capture(), end.capture());

        LocalDate today = LocalDate.now();
        assertEquals(today, end.getValue(), "window must end today, not in the future");
        assertEquals(1, start.getValue().getDayOfMonth(), "window must start on the 1st of a month");

        long monthsSpanned = ChronoUnit.MONTHS.between(
                YearMonth.from(start.getValue()), YearMonth.from(end.getValue())) + 1;
        assertEquals(6, monthsSpanned, "window must span exactly 6 distinct calendar months");
    }

    @Test
    void recordPayment_success() {
        Patient patient = new Patient();
        patient.setId(1L);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(insuranceRepository.save(any())).thenAnswer(inv -> {
            PaidInsurance p = inv.getArgument(0);
            p.setId(10L);
            return p;
        });

        InsurancePaymentRequest req = new InsurancePaymentRequest(LocalDate.of(2026, 8, 1), LocalDate.now());
        assertDoesNotThrow(() -> insuranceService.recordPayment(1L, req, TestAuth.admin()));
        verify(insuranceRepository).save(any());
    }

    // ──────────────────────────────────────────────
    // The справка §1 asks for: reported, not reduced to a flag
    // ──────────────────────────────────────────────

    @Test
    void getStatus_reportsTheMonthCountAlongsideTheVerdict() {
        when(insuranceRepository.countCoveredMonths(eq(1L), any(), any())).thenReturn(6L);

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertTrue(status.insured());
        assertEquals(6, status.coveredMonths());
        assertEquals(6, status.requiredMonths());
    }

    /**
     * One month short is the boundary the rule turns on, and the case where saying *how* short
     * matters — it is the difference between a reminder and a refusal.
     */
    @Test
    void getStatus_oneMonthShortIsNotInsuredButSaysHowShort() {
        when(insuranceRepository.countCoveredMonths(eq(1L), any(), any())).thenReturn(5L);

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertFalse(status.insured());
        assertEquals(5, status.coveredMonths());
    }

    /**
     * A patient who has never paid has no row in the grouped query at all. A report of who is
     * covered has to name the people who are not, so they are folded back in at zero rather than
     * silently dropped.
     */
    @Test
    void getStatusForAllPatients_includesPatientsWithNoPaymentsAtZero() {
        Patient paid = new Patient();
        paid.setId(1L); paid.setFirstName("Ivan"); paid.setLastName("Dimitrov"); paid.setEgn("8001156240");
        Patient never = new Patient();
        never.setId(2L); never.setFirstName("Petar"); never.setLastName("Ivanov"); never.setEgn("7504221485");

        when(patientRepository.findAll()).thenReturn(java.util.List.of(paid, never));
        // Explicit type argument: List.of(new Object[]{...}) would spread the array into varargs
        // and produce a two-element List<Object> rather than one row.
        when(insuranceRepository.countCoveredMonthsForAllPatients(any(), any()))
                .thenReturn(java.util.List.<Object[]>of(new Object[]{1L, 6L}));

        var rows = insuranceService.getStatusForAllPatients();

        assertEquals(2, rows.size());
        assertTrue(rows.get(0).insured());
        assertEquals(6, rows.get(0).coveredMonths());
        assertEquals("Ivan Dimitrov", rows.get(0).patientName());

        assertFalse(rows.get(1).insured());
        assertEquals(0, rows.get(1).coveredMonths());
        assertEquals("7504221485", rows.get(1).egn());
    }
}
