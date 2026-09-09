package com.f106317.MedicalRecordsSpringWS.Data.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;

import java.time.LocalDate;

/**
 * A sick leave issued at a visit. At most one per visit.
 * WHY no explicit unique constraint on visit_id: Hibernate derives one from {@code @OneToOne} on the
 * owning join column, so the 1:1 cardinality is already enforced in every generated schema. It is
 * spelled out in init.sql only because that file is written by hand.
 */
@Entity
@Table(name = "sick_leave", indexes = @Index(name = "idx_sl_valid_from", columnList = "valid_from"))
// Mirrors chk_valid_for_days in init.sql. SickLeaveRequest carries the same bound as @Min/@Max, so a
// request can never reach this; it is the backstop for anything writing outside the API.
@Check(name = "chk_valid_for_days", constraints = "valid_for_days > 0 AND valid_for_days <= 365")
@Getter
@Setter
@NoArgsConstructor
public class SickLeave extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_for_days", nullable = false)
    private int validForDays;
}
