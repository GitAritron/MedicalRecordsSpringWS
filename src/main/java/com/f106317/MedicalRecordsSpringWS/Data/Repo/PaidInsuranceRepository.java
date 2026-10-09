package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.PaidInsurance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PaidInsuranceRepository extends JpaRepository<PaidInsurance, Long> {

    // Checked before recording a payment. Normalised to 1st of month = month key.
    boolean existsByPatientIdAndInsuranceDate(Long patientId, LocalDate insuranceDate);

    // Same lookup but returns the row - needed when correcting a payment (doesn't conflict with itself).
    java.util.Optional<PaidInsurance> findByPatientIdAndInsuranceDate(Long patientId, LocalDate insuranceDate);

    // Individual payments for correction view.
    List<PaidInsurance> findByPatientIdOrderByInsuranceDateDesc(Long patientId);


    // Returns covered months as year*100+month keys. Caller walks backwards to find the unbroken run.
    @Query("""
            SELECT DISTINCT YEAR(p.insuranceDate) * 100 + MONTH(p.insuranceDate)
            FROM PaidInsurance p
            WHERE p.patient.id = :patientId
              AND p.insuranceDate >= :windowStart
              AND p.insuranceDate <= :windowEnd
            """)
    List<Integer> findCoveredMonthKeys(@Param("patientId") Long patientId,
                                       @Param("windowStart") LocalDate windowStart,
                                       @Param("windowEnd") LocalDate windowEnd);

    // Same as above but for all patients in one query. Patients with no payments are absent.
    @Query("""
            SELECT DISTINCT p.patient.id, YEAR(p.insuranceDate) * 100 + MONTH(p.insuranceDate)
            FROM PaidInsurance p
            WHERE p.insuranceDate >= :windowStart
              AND p.insuranceDate <= :windowEnd
            """)
    List<Object[]> findCoveredMonthKeysForAllPatients(@Param("windowStart") LocalDate windowStart,
                                                      @Param("windowEnd") LocalDate windowEnd);
}
