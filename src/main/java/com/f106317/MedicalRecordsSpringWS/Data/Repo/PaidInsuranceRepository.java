package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.PaidInsurance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface PaidInsuranceRepository extends JpaRepository<PaidInsurance, Long> {

    /**
     * WHAT: Counts the distinct calendar months covered by payments inside a closed date window.
     * WHY: Continuous coverage means one payment per month, so we count distinct year-month pairs
     * rather than rows — several payments in the same month still count once. The caller compares
     * the result against the number of months required, keeping the threshold out of the query.
     * The window is closed at both ends so that a future-dated payment cannot count towards it.
     */
    @Query("""
            SELECT COUNT(DISTINCT CONCAT(YEAR(p.insuranceDate), '-', MONTH(p.insuranceDate)))
            FROM PaidInsurance p
            WHERE p.patient.id = :patientId
              AND p.insuranceDate >= :windowStart
              AND p.insuranceDate <= :windowEnd
            """)
    long countCoveredMonths(@Param("patientId") Long patientId,
                            @Param("windowStart") LocalDate windowStart,
                            @Param("windowEnd") LocalDate windowEnd);
}
