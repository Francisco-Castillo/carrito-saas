package com.carrito.saas.repository.entity;

import java.time.LocalDate;
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
 * UNIQUE (business_id, date, position) — declarada en la tabla con nombre
 * explícito, misma política que {@link BusinessHours} — garantiza a lo sumo un
 * turno por ordinal dentro de la fecha, y junto con el chequeo de duplicados
 * de {@code BusinessProfileServiceImpl}, a lo sumo una entrada por fecha.</p>
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
        uniqueConstraints = @UniqueConstraint(name = "uq_business_date_exception_business_date_position",
                columnNames = {"business_id", "date", "position"}))
public class BusinessDateException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;

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

}
