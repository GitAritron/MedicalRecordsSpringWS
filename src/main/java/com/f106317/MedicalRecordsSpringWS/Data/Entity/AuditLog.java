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

/**
 * An append-only record of who changed what.
 * WHY no association to the audited row: an audit entry has to outlive the record it describes, and
 * a deletion is the event most worth keeping. A real foreign key would either block the delete or
 * cascade the evidence away, so (entityType, entityId) is a deliberate soft reference and
 * entityLabel keeps a readable snapshot of a row that may no longer exist.
 */
@Entity
@Table(name = "audit_log", indexes = {          // mirrors init.sql
        @Index(name = "idx_al_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_al_actor", columnList = "actor"),
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

    /**
     * Who or what the entry refers to, captured when the action happened.
     * WHY store it rather than resolve it on read: an entry frequently describes a record that has
     * since been deleted, and an id alone then names something an administrator cannot look up.
     */
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
