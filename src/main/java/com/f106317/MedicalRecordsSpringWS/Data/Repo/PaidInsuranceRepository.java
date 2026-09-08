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


    /**
     * Which months one patient has paid for, as sortable {@code year * 100 + month} keys.
     * <p>
     * WHY the months rather than a count: a count says how many of the six were paid, not whether
     * they run unbroken back from today. Someone who paid five months and then stopped is not
     * currently insured, but counts the same as someone who missed only the oldest month. The
     * caller walks the keys backwards from the current month to find the unbroken run.
     */
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

    /**
     * The same months for every patient who has paid anything inside the window, in one query.
     * Patients with no payments are absent from the result; the caller folds them back in at zero
     * rather than joining them here.
     */
    @Query("""
            SELECT DISTINCT p.patient.id, YEAR(p.insuranceDate) * 100 + MONTH(p.insuranceDate)
            FROM PaidInsurance p
            WHERE p.insuranceDate >= :windowStart
              AND p.insuranceDate <= :windowEnd
            """)
    List<Object[]> findCoveredMonthKeysForAllPatients(@Param("windowStart") LocalDate windowStart,
                                                      @Param("windowEnd") LocalDate windowEnd);
}
