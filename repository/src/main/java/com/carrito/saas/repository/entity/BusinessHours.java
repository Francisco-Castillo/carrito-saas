package com.carrito.saas.repository.entity;

import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * <p>NULL-scope (odd/tasks/multi-sucursal.md, fix CI run 40619938257): in
 * Postgres two NULLs are DISTINCT from each other, so the 4-column constraint
 * alone does NOT reject two legacy rows (branch_id NULL) with the same
 * (day_of_week, position) — the duplicate only collided on dev databases that
 * still carried the old 3-column constraint, and entered freely on fresh
 * databases (CI). The invariant is restored with a companion FUNCTIONAL
 * unique index where the branch scope is coalesced to a sentinel
 * (COALESCE(branch_id, -1)): two legacy rows share the -1 sentinel and
 * collide, rows of different branches don't — the standard Postgres idiom
 * for "NULLs compare as equal" (the pre-15 equivalent of
 * {@code NULLS NOT DISTINCT}), valid for BOTH scopes with one index.
 * Hibernate can't express it as a constraint (constraint DDL carries no
 * predicate and Hibernate appends constraint options AFTER the column list,
 * which PostgreSQL rejects), so it is declared as a JPA {@code @Index} whose
 * parenthesized {@code columnList} token Hibernate treats as an expression
 * — that forces a real {@code CREATE UNIQUE INDEX} out of ddl-auto on fresh
 * databases, no out-of-band DDL needed. Together with the 4-column
 * constraint above, the invariant holds in both scopes.
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
                columnNames = {"business_id", "branch_id", "day_of_week", "position"}),
        // Legacy scope (branch_id NULL): Postgres trata dos NULL como
        // distintos, así que la única de 4 columnas no rechaza dos filas
        // legacy con el mismo (día, posición). Este índice único FUNCIONAL
        // coalescea el ámbito a un centinela (COALESCE(branch_id, -1)): dos
        // filas legacy comparten -1 y chocan, y las distintas sucursales
        // conservan ámbitos independientes. El token entre paréntesis del
        // columnList es una expresión para Hibernate: fuerza un CREATE
        // UNIQUE INDEX real (una constraint no puede llevar la expresión y
        // Hibernate pega las options DESPUÉS de la lista de columnas, que
        // PostgreSQL rechaza). ddl-auto lo crea también en bases nuevas
        // (odd/tasks/multi-sucursal.md, fix CI run 40619938257). Sin comas
        // dentro de la expresión: Hibernate parte el columnList por ','.
        indexes = @Index(name = "uq_business_hours_business_day_position_effective",
                columnList = "business_id, day_of_week, position, "
                        + "(CASE WHEN branch_id IS NULL THEN -1 ELSE branch_id END)",
                unique = true))
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
