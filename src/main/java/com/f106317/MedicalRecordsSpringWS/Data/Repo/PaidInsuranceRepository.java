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

    /**
     * The same count, for every patient who has paid anything inside the window, in one query.
     * WHY: the practice-wide view needs coverage for the whole roster, and asking per patient would
     * be one round trip each. Patients with no payments at all are simply absent from the result —
     * the caller treats a missing row as zero rather than joining them back in here.
     */
    @Query("""
            SELECT p.patient.id,
                   COUNT(DISTINCT CONCAT(YEAR(p.insuranceDate), '-', MONTH(p.insuranceDate)))
            FROM PaidInsurance p
            WHERE p.insuranceDate >= :windowStart
              AND p.insuranceDate <= :windowEnd
            GROUP BY p.patient.id
            """)
    List<Object[]> countCoveredMonthsForAllPatients(@Param("windowStart") LocalDate windowStart,
                                                    @Param("windowEnd") LocalDate windowEnd);
}
