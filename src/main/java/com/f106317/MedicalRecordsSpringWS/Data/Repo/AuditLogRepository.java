package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AuditLog> findByActionAndEntityTypeOrderByCreatedAtDesc(String action, String entityType);

    /**
     * WHY: lets a caller subtract one action from another — for example, self-registrations that
     * have not yet been approved — without loading whole audit rows just to read their entity id.
     */
    @Query("SELECT DISTINCT a.entityId FROM AuditLog a WHERE a.action = :action AND a.entityType = :entityType")
    Set<Long> findEntityIdsByActionAndEntityType(@Param("action") String action,
                                                 @Param("entityType") String entityType);
}
