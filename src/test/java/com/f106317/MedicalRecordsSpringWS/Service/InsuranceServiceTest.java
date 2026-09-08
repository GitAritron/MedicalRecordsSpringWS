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
import java.util.List;
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

    /** Month keys as the repository returns them: year * 100 + month, counting back from now. */
    private static List<Integer> monthsBack(int howMany) {
        List<Integer> keys = new java.util.ArrayList<>();
        YearMonth m = YearMonth.now();
        for (int i = 0; i < howMany; i++) {
            keys.add(m.getYear() * 100 + m.getMonthValue());
            m = m.minusMonths(1);
        }
        return keys;
    }

    /** All 6 months of the window run unbroken back from this one, so the patient is insured. */
    @Test
    void hasContinuousInsurance_true() {
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(monthsBack(6));
        assertTrue(insuranceService.hasContinuousInsurance(1L));
    }

    /**
     * Five months must fail.
     * WHY: this is the regression guard for the original off-by-one — the old window spanned seven
     * calendar months while requiring only six, so a patient could skip a month and still pass.
     */
    @Test
    void hasContinuousInsurance_falseWhenOneMonthMissing() {
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(monthsBack(5));
        assertFalse(insuranceService.hasContinuousInsurance(1L));
    }

    @Test
    void hasContinuousInsurance_falseWhenNoPayments() {
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(List.of());
        assertFalse(insuranceService.hasContinuousInsurance(1L));
    }

    /**
     * Six paid months that do NOT run back from the current one must fail, and this is the case a
     * plain count cannot see: the patient stopped paying, so they are not insured now, even though
     * six months inside the window carry a payment.
     * <p>
     * The window only holds six months, so a gap can only be produced by a payment older than it —
     * which the query excludes. The run therefore breaks at the missing current month.
     */
    /**
     * Someone who stopped paying two months ago has neither the current month nor the previous one,
     * so there is no anchor and the run is zero — however many older months they paid. This is the
     * case a plain tally cannot see.
     */
    @Test
    void hasContinuousInsurance_zeroWhenTheyStoppedBeforeLastMonth() {
        YearMonth twoBack = YearMonth.now().minusMonths(2);
        List<Integer> olderOnly = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            olderOnly.add(twoBack.getYear() * 100 + twoBack.getMonthValue());
            twoBack = twoBack.minusMonths(1);
        }
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(olderOnly);

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertFalse(status.insured());
        // Neither this month nor last is paid, so there is no anchor and both are owed.
        assertEquals(2, status.unpaidMonths().size());
    }

    /**
     * Pins down the window itself: it must span exactly 6 calendar months, start on the 1st, and
     * end today — so that neither a 7th month nor a future-dated payment can be counted.
     */
    @Test
    void hasContinuousInsurance_windowSpansExactlySixMonthsEndingToday() {
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(monthsBack(6));

        insuranceService.hasContinuousInsurance(1L);

        ArgumentCaptor<LocalDate> start = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> end = ArgumentCaptor.forClass(LocalDate.class);
        verify(insuranceRepository).findCoveredMonthKeys(eq(1L), start.capture(), end.capture());

        LocalDate today = LocalDate.now();
        assertEquals(today, end.getValue(), "window must end today, not in the future");
        assertEquals(1, start.getValue().getDayOfMonth(), "window must start on the 1st of a month");

        // Seven months, not six: a run anchored at last month can reach six months back from there.
        // This is safe only because nothing is counted inside the window — the run must be unbroken
        // from its anchor, so the old "skip a month and still pass" bug cannot come back. The test
        // immediately below is the guard for that.
        long monthsSpanned = ChronoUnit.MONTHS.between(
                YearMonth.from(start.getValue()), YearMonth.from(end.getValue())) + 1;
        assertEquals(7, monthsSpanned, "window must reach the oldest month a run could touch");
    }

    /**
     * The regression guard for the original off-by-one. Seven months of data are fetched, but a
     * patient who skipped a month must still fail however many payments they made.
     */
    @Test
    void hasContinuousInsurance_falseWhenAMonthIsSkippedInsideTheRun() {
        List<Integer> withAGap = new java.util.ArrayList<>(monthsBack(7));
        withAGap.remove(2);   // paid every month except the one two back
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(withAGap);

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertFalse(status.insured(), "a gap must break the run no matter how many months are paid");
        assertEquals(1, status.unpaidMonths().size(), "exactly the skipped month is owed");
    }

    /**
     * The current month is not yet over, so not having paid it is not a lapse. A patient covered
     * unbroken through last month is insured, exactly as an unpaid current bill is not yet overdue.
     */
    @Test
    void hasContinuousInsurance_trueWhenTheRunEndsLastMonthAndThisOneIsNotYetPaid() {
        List<Integer> throughLastMonth = new java.util.ArrayList<>();
        YearMonth m = YearMonth.now().minusMonths(1);
        for (int i = 0; i < 6; i++) {
            throughLastMonth.add(m.getYear() * 100 + m.getMonthValue());
            m = m.minusMonths(1);
        }
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(throughLastMonth);

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertTrue(status.insured());
        // Insured, and this month is still listed as owed — which is precisely the position:
        // covered, with the current bill outstanding.
        assertEquals(1, status.unpaidMonths().size());
        assertEquals(YearMonth.now().getMonthValue(), status.unpaidMonths().get(0).month());
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
    void getStatus_reportsNothingOwedWhenFullyCovered() {
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(monthsBack(6));

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertTrue(status.insured());
        assertTrue(status.unpaidMonths().isEmpty(), "nothing owed when every month is paid");
    }

    /**
     * One month short is the boundary the rule turns on, and the case where saying *how* short
     * matters — it is the difference between a reminder and a refusal.
     */
    @Test
    void getStatus_oneMonthShortIsNotInsuredAndNamesTheMonth() {
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(monthsBack(5));

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertFalse(status.insured());
        assertEquals(1, status.unpaidMonths().size(), "only the oldest month in the window is owed");
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
        // One row per covered month, as the grouped query returns them.
        List<Object[]> rowsFromDb = new java.util.ArrayList<>();
        for (Integer key : monthsBack(6)) {
            rowsFromDb.add(new Object[]{1L, key});
        }
        when(insuranceRepository.findCoveredMonthKeysForAllPatients(any(), any())).thenReturn(rowsFromDb);

        var rows = insuranceService.getStatusForAllPatients();

        assertEquals(2, rows.size());
        assertTrue(rows.get(0).insured());
        assertEquals("Ivan Dimitrov", rows.get(0).patientName());

        assertFalse(rows.get(1).insured());
        assertEquals("7504221485", rows.get(1).egn());
        // Never paid, so every month in the window is owed — and named, not just counted.
        assertEquals(6, rows.get(1).unpaidMonths().size());
        assertTrue(rows.get(0).unpaidMonths().isEmpty(), "fully covered patients owe nothing");
    }
}
