package com.carrito.saas.repository.entity;

import java.time.LocalDate;
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
 * Una fila por turno de una fecha especial del negocio (feriado u horario
 * especial), ordenada dentro de la fecha por {@code position}. Misma forma que
 * {@link BusinessHours} pero con clave de calendario: el día de la semana se
 * repite toda la vida y vive en {@code business_hours}; una fecha excepcional
 * vive una vez y vive acá.
 *
 * <p>La fecha es la unidad: un feriado cerrado se guarda como UNA fila
 * marcadora con {@code position = 0} y {@code opensAt}/{@code closesAt} en
 * NULL — a diferencia de un día de la semana, donde la ausencia de filas ya
 * significa cerrado, acá la ausencia de la fecha significa "día normal", así
 * que el cerrado tiene que estar representado. La única
 * UNIQUE (business_id, branch_id, date, position) — declarada en la tabla con
 * nombre explícito, misma política que {@link BusinessHours} — garantiza a lo
 * sumo un turno por ordinal dentro de la fecha de una sucursal, y junto con
 * el chequeo de duplicados de {@code BusinessProfileServiceImpl}, a lo sumo
 * una entrada por fecha.</p>
 *
 * <p>Ámbito NULL (odd/tasks/multi-sucursal.md, fix CI run 40619938257): en
 * Postgres dos NULL son distintos entre sí, así que la única de 4 columnas
 * sola NO rechaza dos filas legacy (branch_id NULL) de la misma fecha y
 * posición — en dev lo escondía la vieja única de 3 columnas y en CI entraban
 * libres. Mismo cierre que {@link BusinessHours}: un índice único FUNCIONAL
 * con el ámbito coalesceado a centinela (COALESCE(branch_id, -1)), declarado
 * vía JPA {@code @Index} con el token entre paréntesis para que ddl-auto lo
 * emita también en bases nuevas.</p>
 *
 * <p>Un turno cuyo {@code closesAt} es anterior a su {@code opensAt} cruza la
 * medianoche (una noche especial 19:00 → 00:30 se guarda tal cual); la
 * igualdad y el resto de las reglas se rechazan en
 * {@code BusinessProfileServiceImpl} antes de guardar, no por esquema.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "business_date_exception",
        uniqueConstraints = @UniqueConstraint(name = "uq_business_date_exception_business_branch_date_position",
                columnNames = {"business_id", "branch_id", "date", "position"}),
        // Legacy scope (branch_id NULL): mismas razones que en BusinessHours
        // — la única de 4 columnas no ve duplicados entre NULLs y este índice
        // único funcional con centinela los rechaza en ambos ámbitos (ddl-auto
        // lo emite también en bases nuevas; odd/tasks/multi-sucursal.md).
        indexes = @Index(name = "uq_business_date_exception_business_date_position_effective",
                columnList = "business_id, date, position, "
                        + "(CASE WHEN branch_id IS NULL THEN -1 ELSE branch_id END)",
                unique = true))
public class BusinessDateException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;

    /**
     * Sucursal dueña de la excepción (multi-sucursal,
     * odd/tasks/multi-sucursal.md, S3/T4). NULL = fila legacy a nivel negocio:
     * la heredan las sucursales que no definieron las suyas. La columna queda
     * nullable a propósito (docs/migrations/2026-10-28-branch-hours.sql),
     * mismas razones que en {@link BusinessHours}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** Fecha del calendario (una sola vez por negocio). */
    @Column(name = "date", nullable = false)
    private LocalDate date;

    /** Ordinal del turno dentro de la fecha (0-based); 0 en el marcador de cerrado. */
    @Column(name = "position", nullable = false)
    private Integer position;

    /** NULL junto con {@link #closesAt} en la fila marcadora de fecha cerrada. */
    private LocalTime opensAt;

    /** Anterior a {@link #opensAt} significa que el turno cruza la medianoche. */
    private LocalTime closesAt;

    /**
     * true: la excepción se repite todos los años en el día y mes de
     * {@link #date} (Navidad es 25/12 todos los años); false: sólo esa fecha
     * exacta. El año de {@link #date} es sólo la referencia para la validación
     * de derrame, nunca parte del match anual. Todas las filas de una misma
     * fecha comparten la bandera: se guardan juntas desde el mismo
     * {@code DateExceptionDTO}.
     */
    @Column(name = "yearly", nullable = false)
    private boolean yearly = false;

}
