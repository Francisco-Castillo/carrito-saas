package com.carrito.saas.repository.entity;

import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * One row per shift of a business's opening hours ({@code dayOfWeek}
 * 1 = Monday … 7 = Sunday, ISO convention), ordered inside the day by
 * {@code position}. Owned by the storefront profile: the owner replaces the
 * whole list via {@code PUT /api/business/profile}, so the
 * UNIQUE (business_id, branch_id, day_of_week, position) constraint — declared
 * on the table with an explicit name, same policy as {@link Business}'s
 * constraints — guarantees at most one shift per ordinal within a day of a
 * branch.
 *
 * <p>This is the target shape of the multi-shift change
 * (docs/migrations/2026-10-15-business-hours-shifts.sql): a day holds one or
 * more ordered shifts, and a day with no rows is a closed day — the removed
 * {@code closed} column can no longer contradict the hours.
 * A shift whose {@code closesAt} is earlier than its {@code opensAt} is legal:
 * it crosses midnight and ends in the early hours of the next day (a Friday
 * 11:00 → 00:30 closes at 00:30 the following Saturday); the equality is
 * rejected in {@code BusinessProfileServiceImpl} before save, not by the
 * schema.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "business_hours",
        uniqueConstraints = @UniqueConstraint(name = "uq_business_hours_business_branch_day_position",
                columnNames = {"business_id", "branch_id", "day_of_week", "position"}))
public class BusinessHours {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;

    /**
     * Sucursal dueña del turno (multi-sucursal, odd/tasks/multi-sucursal.md,
     * S3/T4). NULL = fila legacy a nivel negocio: la escribió el perfil antes
     * de las sucursales (o el negocio todavía no tiene ninguna) y la heredan
     * las sucursales que no definieron las suyas. La columna queda nullable a
     * propósito (docs/migrations/2026-10-28-branch-hours.sql): la atribución
     * la garantiza el servicio, no el esquema — un NOT NULL rompería las filas
     * legacy y los negocios sin sucursales.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** ISO day: 1 = Monday … 7 = Sunday. */
    @Column(name = "day_of_week", nullable = false)
    private Integer dayOfWeek;

    /** Ordinal of the shift inside the day (0-based). */
    @Column(name = "position", nullable = false)
    private Integer position;

    /** Present together with {@link #closesAt}; a day with no rows is closed. */
    private LocalTime opensAt;

    /** Earlier than {@link #opensAt} means the shift crosses midnight. */
    private LocalTime closesAt;

}
