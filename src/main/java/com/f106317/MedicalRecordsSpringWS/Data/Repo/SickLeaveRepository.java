package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.SickLeave;
import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SickLeaveRepository extends JpaRepository<SickLeave, Long> {
    // Report: month with most sick leaves. Groups by year+month to avoid cross-year aggregation.
    @Query("""
            SELECT new com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO$SickLeaveMonthCount(
                       YEAR(s.validFrom), MONTH(s.validFrom), COUNT(s))
            FROM SickLeave s
            GROUP BY YEAR(s.validFrom), MONTH(s.validFrom)
            ORDER BY COUNT(s) DESC
            """)
    List<ReportDTO.SickLeaveMonthCount> findMonthsWithMostSickLeaves();
    
    // Report: which doctor issued the most sick leaves.
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
