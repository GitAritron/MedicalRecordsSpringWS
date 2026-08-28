package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.PaidInsurance;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PaidInsuranceRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PatientRepository;
import com.f106317.MedicalRecordsSpringWS.DTO.InsurancePaymentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @Test
    void hasContinuousInsurance_true() {
        when(insuranceRepository.hasContinuousInsurance(eq(1L), any())).thenReturn(true);
        assertTrue(insuranceService.hasContinuousInsurance(1L));
    }

    @Test
    void hasContinuousInsurance_false() {
        when(insuranceRepository.hasContinuousInsurance(eq(1L), any())).thenReturn(false);
        assertFalse(insuranceService.hasContinuousInsurance(1L));
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

        InsurancePaymentRequest req = new InsurancePaymentRequest(1L, LocalDate.of(2026, 8, 1), LocalDate.now());
        assertDoesNotThrow(() -> insuranceService.recordPayment(req, null));
        verify(insuranceRepository).save(any());
    }
}
