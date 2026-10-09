package com.f106317.MedicalRecordsSpringWS.Data.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// Append-only audit trail. Uses (entityType, entityId) instead of FK - entries must survive record deletion.
@Entity
@Table(name = "audit_log", indexes = {          // mirrors init.sql
        @Index(name = "idx_al_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_al_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AuditLog extends BaseEntity {

    @Column(name = "actor", nullable = false, length = 100)
    private String actor;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    // Snapshot of who/what this refers to, stored at write time because the record may be deleted later.
    @Column(name = "entity_label", length = 150)
    private String entityLabel;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
