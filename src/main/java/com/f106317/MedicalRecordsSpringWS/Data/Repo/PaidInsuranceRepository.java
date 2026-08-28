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
     * Checks if a patient has paid insurance for ALL months in the last 6 months.
     * Counts distinct year-month combinations to ensure continuous coverage.
     */
    @Query("SELECT COUNT(DISTINCT CONCAT(YEAR(p.insuranceDate), '-', MONTH(p.insuranceDate))) >= 6 FROM PaidInsurance p WHERE p.patient.id = :patientId AND p.insuranceDate >= :sixMonthsAgo")
    boolean hasContinuousInsurance(@Param("patientId") Long patientId, @Param("sixMonthsAgo") LocalDate sixMonthsAgo);
}
