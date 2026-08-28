package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.SickLeave;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SickLeaveRepository extends JpaRepository<SickLeave, Long> {
    /**
     * WHAT: A JPQL query that extracts the year and month from the sick leave start date and groups by both.
     * WHY: Fulfills the requirement "Information for the month in the year when most sick leaves were issued".
     * Groups by both year AND month to avoid cross-year aggregation bugs.
     */
    @Query("SELECT YEAR(s.validFrom) as yr, MONTH(s.validFrom) as mo, COUNT(s) as c FROM SickLeave s GROUP BY YEAR(s.validFrom), MONTH(s.validFrom) ORDER BY c DESC")
    List<Object[]> findMonthsWithMostSickLeaves();
    
    /**
     * WHAT: A JPQL query that groups sick leaves by the issuing doctor via the Visit entity.
     * WHY: Fulfills the requirement "Information on the doctor(s) who issued the most sick leaves".
     */
    @Query("SELECT s.visit.doctor, COUNT(s) as c FROM SickLeave s GROUP BY s.visit.doctor ORDER BY c DESC")
    List<Object[]> findDoctorsWithMostSickLeaves();
}
