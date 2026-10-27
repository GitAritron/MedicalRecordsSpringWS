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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    // Month keys as the repository returns them (year * 100 + month), counting back from now.
    private static List<Integer> monthsBack(int howMany) {
        List<Integer> keys = new java.util.ArrayList<>();
        YearMonth m = YearMonth.now();
        for (int i = 0; i < howMany; i++) {
            keys.add(m.getYear() * 100 + m.getMonthValue());
            m = m.minusMonths(1);
        }
        return keys;
    }

    @Test
    void insuredVerdict_true() {
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(monthsBack(6));
        assertTrue(insuranceService.getStatus(1L).insured());
    }

    @Test
    void insuredVerdict_falseWhenNoPayments() {
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(List.of());
        assertFalse(insuranceService.getStatus(1L).insured());
    }

    // Stopped two months ago: no anchor month, so the run is zero however much they paid before.
    @Test
    void insuredVerdict_zeroWhenTheyStoppedBeforeLastMonth() {
        YearMonth twoBack = YearMonth.now().minusMonths(2);
        List<Integer> olderOnly = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            olderOnly.add(twoBack.getYear() * 100 + twoBack.getMonthValue());
            twoBack = twoBack.minusMonths(1);
        }
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(olderOnly);

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertFalse(status.insured());
        // No anchor, so the oldest month of the window, last month and this month are all owed.
        assertEquals(3, status.unpaidMonths().size());
    }

    // The window starts on the 1st and ends today, so no future-dated payment can count.
    @Test
    void insuredVerdict_windowSpansSevenMonthsFromTheFirstToToday() {
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(monthsBack(6));

        insuranceService.getStatus(1L).insured();

        ArgumentCaptor<LocalDate> start = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> end = ArgumentCaptor.forClass(LocalDate.class);
        verify(insuranceRepository).findCoveredMonthKeys(eq(1L), start.capture(), end.capture());

        LocalDate today = LocalDate.now();
        assertEquals(today, end.getValue(), "window must end today, not in the future");
        assertEquals(1, start.getValue().getDayOfMonth(), "window must start on the 1st of a month");

        // Seven, not six: a run anchored at last month reaches six back from there.
        // Safe because the run must be unbroken - the next test guards that.
        long monthsSpanned = ChronoUnit.MONTHS.between(
                YearMonth.from(start.getValue()), YearMonth.from(end.getValue())) + 1;
        assertEquals(7, monthsSpanned, "window must reach the oldest month a run could touch");
    }

    // Skipping a month must fail however many payments there are.
    @Test
    void insuredVerdict_falseWhenAMonthIsSkippedInsideTheRun() {
        List<Integer> withAGap = new java.util.ArrayList<>(monthsBack(7));
        withAGap.remove(2);   // paid every month except the one two back
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(withAGap);

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertFalse(status.insured(), "a gap must break the run no matter how many months are paid");
        assertEquals(1, status.unpaidMonths().size(), "exactly the skipped month is owed");
    }

    // The current month isn't over yet, so not paying it isn't a lapse.
    @Test
    void insuredVerdict_trueWhenTheRunEndsLastMonthAndThisOneIsNotYetPaid() {
        List<Integer> throughLastMonth = new java.util.ArrayList<>();
        YearMonth m = YearMonth.now().minusMonths(1);
        for (int i = 0; i < 6; i++) {
            throughLastMonth.add(m.getYear() * 100 + m.getMonthValue());
            m = m.minusMonths(1);
        }
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(throughLastMonth);

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertTrue(status.insured());
        // Insured, with the current month still listed as owed.
        assertEquals(1, status.unpaidMonths().size());
        assertEquals(YearMonth.now().getMonthValue(), status.unpaidMonths().get(0).month());
    }

    // Paid the last five months, not this one: the verdict counts back from last month, so the gap is 6 months ago.
    @Test
    void getStatus_namesTheMonthThatBreaksTheRunWhenThisMonthIsUnpaid() {
        List<Integer> lastFive = new java.util.ArrayList<>();
        YearMonth m = YearMonth.now().minusMonths(1);
        for (int i = 0; i < 5; i++) {
            lastFive.add(m.getYear() * 100 + m.getMonthValue());
            m = m.minusMonths(1);
        }
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(lastFive);

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertFalse(status.insured());
        YearMonth sixBack = YearMonth.now().minusMonths(6);
        assertEquals(2, status.unpaidMonths().size());
        assertEquals(sixBack.getMonthValue(), status.unpaidMonths().get(0).month(), "the month that breaks the run");
        assertEquals(YearMonth.now().getMonthValue(), status.unpaidMonths().get(1).month(), "the current month");
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

    // The covered month is the record's identity.
    @Test
    void recordPayment_normalisesTheCoveredMonthToTheFirst() {
        Patient patient = new Patient();
        patient.setId(1L);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(insuranceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalDate paidOn = LocalDate.of(2026, 8, 27);
        insuranceService.recordPayment(1L,
                new InsurancePaymentRequest(LocalDate.of(2026, 8, 27), paidOn), TestAuth.admin());

        ArgumentCaptor<PaidInsurance> saved = ArgumentCaptor.forClass(PaidInsurance.class);
        verify(insuranceRepository).save(saved.capture());

        assertEquals(LocalDate.of(2026, 8, 1), saved.getValue().getInsuranceDate(),
                "the covered month must be stored as the first of that month");
        assertEquals(paidOn, saved.getValue().getPaidOn(),
                "paidOn is when the money actually arrived and must survive untouched");
    }

    @Test
    void recordPayment_rejectsASecondPaymentForAMonthAlreadyCovered() {
        Patient patient = new Patient();
        patient.setId(1L);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(insuranceRepository.existsByPatientIdAndInsuranceDate(1L, LocalDate.of(2026, 8, 1)))
                .thenReturn(true);

        // A different day in the same month - the case the old whole-date comparison let through.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> insuranceService.recordPayment(1L,
                        new InsurancePaymentRequest(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 14)),
                        TestAuth.admin()));

        assertTrue(ex.getMessage().contains("2026-08"), "the message should name the month at issue");
        verify(insuranceRepository, never()).save(any());
    }


    @Test
    void getStatus_reportsNothingOwedWhenFullyCovered() {
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(monthsBack(6));

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertTrue(status.insured());
        assertTrue(status.unpaidMonths().isEmpty(), "nothing owed when every month is paid");
    }

    // One month short is the boundary
    @Test
    void getStatus_oneMonthShortIsNotInsuredAndNamesTheMonth() {
        when(insuranceRepository.findCoveredMonthKeys(eq(1L), any(), any())).thenReturn(monthsBack(5));

        InsuranceStatusResponse status = insuranceService.getStatus(1L);

        assertFalse(status.insured());
        assertEquals(1, status.unpaidMonths().size(), "only the oldest month in the window is owed");
    }

    // Never-paid patients have no row in the grouped query, so they're folded back in at zero.
    @Test
    void getStatusForAllPatients_includesPatientsWithNoPaymentsAtZero() {
        Patient paid = new Patient();
        paid.setId(1L); paid.setFirstName("Ivan"); paid.setLastName("Dimitrov"); paid.setEgn("8001156240");
        Patient never = new Patient();
        never.setId(2L); never.setFirstName("Petar"); never.setLastName("Ivanov"); never.setEgn("7504221485");

        when(patientRepository.findAll()).thenReturn(java.util.List.of(paid, never));
        // Explicit type: List.of(new Object[]{...}) would spread the array into varargs.
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
        // Never paid, so every month in the window is owed - and named, not just counted.
        assertEquals(7, rows.get(1).unpaidMonths().size());
        assertTrue(rows.get(0).unpaidMonths().isEmpty(), "fully covered patients owe nothing");
    }

    // ──────────────────────────────────────────────
    // Correcting a recorded contribution
    // ──────────────────────────────────────────────

    private static Patient patientWithId(long id) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.setFirstName("Ivan");
        patient.setLastName("Dimitrov");
        return patient;
    }

    private static PaidInsurance paymentOn(long id, Patient patient, LocalDate coveredMonth) {
        PaidInsurance payment = new PaidInsurance();
        payment.setId(id);
        payment.setPatient(patient);
        payment.setInsuranceDate(coveredMonth);
        payment.setPaidOn(coveredMonth.plusDays(4));
        return payment;
    }

    // insuranceDate is the month key, so a correction normalises it just like recordPayment.
    @Test
    void updatePayment_normalisesTheCoveredMonthToTheFirst() {
        Patient patient = patientWithId(1L);
        PaidInsurance existing = paymentOn(9L, patient, LocalDate.of(2026, 2, 1));
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(insuranceRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(insuranceRepository.findByPatientIdAndInsuranceDate(1L, LocalDate.of(2026, 3, 1)))
                .thenReturn(Optional.empty());
        when(insuranceRepository.save(any(PaidInsurance.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = insuranceService.updatePayment(1L,  9L,
                new InsurancePaymentRequest(LocalDate.of(2026, 3, 17), LocalDate.of(2026, 3, 20)),
                TestAuth.admin());

        assertEquals(LocalDate.of(2026, 3, 1), response.insuranceDate(), "the covered month, not the day picked");
        assertEquals(LocalDate.of(2026, 3, 20), response.paidOn(), "paidOn is when the money arrived");
    }

    @Test
    void updatePayment_refusesToMoveOntoAMonthAnotherPaymentCovers() {
        Patient patient = patientWithId(1L);
        PaidInsurance existing = paymentOn(9L, patient, LocalDate.of(2026, 2, 1));
        PaidInsurance marchAlreadyPaid = paymentOn(10L, patient, LocalDate.of(2026, 3, 1));
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(insuranceRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(insuranceRepository.findByPatientIdAndInsuranceDate(1L, LocalDate.of(2026, 3, 1)))
                .thenReturn(Optional.of(marchAlreadyPaid));

        assertThrows(IllegalArgumentException.class, () -> insuranceService.updatePayment(1L, 9L,
                new InsurancePaymentRequest(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 20)),
                TestAuth.admin()));
        verify(insuranceRepository, never()).save(any());
    }

    @Test
    void updatePayment_keepingTheSameMonthIsNotAConflict() {
        Patient patient = patientWithId(1L);
        PaidInsurance existing = paymentOn(9L, patient, LocalDate.of(2026, 2, 1));
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(insuranceRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(insuranceRepository.findByPatientIdAndInsuranceDate(1L, LocalDate.of(2026, 2, 1)))
                .thenReturn(Optional.of(existing));
        when(insuranceRepository.save(any(PaidInsurance.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = assertDoesNotThrow(() -> insuranceService.updatePayment(1L, 9L,
                new InsurancePaymentRequest(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 5, 2)),
                TestAuth.admin()));

        assertEquals(LocalDate.of(2026, 5, 2), response.paidOn(), "a late payment still covers February");
    }

    // Otherwise one patient's payment could be edited under another's URL, and the audit entry would name the wrong person.
    @Test
    void deletePayment_refusesAPaymentBelongingToAnotherPatient() {
        Patient thisPatient = patientWithId(1L);
        PaidInsurance someoneElses = paymentOn(9L, patientWithId(2L), LocalDate.of(2026, 2, 1));
        when(patientRepository.findById(1L)).thenReturn(Optional.of(thisPatient));
        when(insuranceRepository.findById(9L)).thenReturn(Optional.of(someoneElses));

        assertThrows(com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException.class,
                () -> insuranceService.deletePayment(1L, 9L, TestAuth.admin()));
        verify(insuranceRepository, never()).delete(any());
    }

    // The row is gone afterwards, so the audit entry has to name the month.
    @Test
    void deletePayment_recordsWhichMonthWasWithdrawn() {
        Patient patient = patientWithId(1L);
        PaidInsurance february = paymentOn(9L, patient, LocalDate.of(2026, 2, 1));
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(insuranceRepository.findById(9L)).thenReturn(Optional.of(february));

        insuranceService.deletePayment(1L, 9L, TestAuth.admin());

        verify(insuranceRepository).delete(february);
        ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).log(any(), eq("DELETE"), eq("PaidInsurance"), eq(9L), label.capture());
        assertTrue(label.getValue().contains("2026-02"), "the withdrawn month must survive the row");
    }

    @Test
    void listPayments_returnsWhatWasRecorded() {
        Patient patient = patientWithId(1L);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(insuranceRepository.findByPatientIdOrderByInsuranceDateDesc(1L)).thenReturn(List.of(
                paymentOn(10L, patient, LocalDate.of(2026, 3, 1)),
                paymentOn(9L, patient, LocalDate.of(2026, 2, 1))));

        var payments = insuranceService.listPayments(1L);

        assertEquals(2, payments.size());
        assertEquals(LocalDate.of(2026, 3, 1), payments.get(0).insuranceDate(), "newest covered month first");
        assertEquals(1L, payments.get(0).patientId());
    }
}
