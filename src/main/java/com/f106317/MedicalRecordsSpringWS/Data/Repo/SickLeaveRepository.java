package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.SickLeave;
import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
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
    @Query("""
            SELECT new com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO$SickLeaveMonthCount(
                       YEAR(s.validFrom), MONTH(s.validFrom), COUNT(s))
            FROM SickLeave s
            GROUP BY YEAR(s.validFrom), MONTH(s.validFrom)
            ORDER BY COUNT(s) DESC
            """)
    List<ReportDTO.SickLeaveMonthCount> findMonthsWithMostSickLeaves();
    
    /**
     * WHAT: A JPQL query that groups sick leaves by the issuing doctor via the Visit entity.
     * WHY: Fulfills the requirement "Information on the doctor(s) who issued the most sick leaves".
     */
    @Query("""
            SELECT new com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO$DoctorSickLeaveCount(
                       s.visit.doctor.id,
                       CONCAT(s.visit.doctor.firstName, ' ', s.visit.doctor.lastName),
                       COUNT(s))
            FROM SickLeave s
            GROUP BY s.visit.doctor.id, s.visit.doctor.firstName, s.visit.doctor.lastName
            ORDER BY COUNT(s) DESC
            """)
    List<ReportDTO.DoctorSickLeaveCount> findDoctorsWithMostSickLeaves();
}
