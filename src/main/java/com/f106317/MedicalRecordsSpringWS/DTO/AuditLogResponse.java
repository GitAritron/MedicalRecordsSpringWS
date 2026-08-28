package com.f106317.MedicalRecordsSpringWS.DTO;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.AuditLog;

import java.time.LocalDateTime;

public record AuditLogResponse(
        Long id,
        String actor,
        String action,
        String entityType,
        Long entityId,
        LocalDateTime createdAt
) {
    public static AuditLogResponse fromEntity(AuditLog auditLog) {
        return new AuditLogResponse(
                auditLog.getId(),
                auditLog.getActor(),
                auditLog.getAction(),
                auditLog.getEntityType(),
                auditLog.getEntityId(),
                auditLog.getCreatedAt()
        );
    }
}
