package com.carrito.saas.dto;

import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * Una fecha especial del negocio (feriado u horario especial) en el perfil del
 * dueño y en el menú público. Tiene la misma forma que un día de la semana:
 * turnos ordenados con la lista vacía significando cerrado — así el DTO, la
 * validación y el cálculo de la carta reusan lo que ya existe. En el PUT la
 * lista se reemplaza en bloque y una fecha ausente vuelve a ser un día
 * normal; una fecha presente sin la clave {@code shifts} es error (400), no
 * un día normal, por el mismo guard que en {@link BusinessHoursDTO}.
 */
@Data
public class DateExceptionDTO {

    /** Fecha del calendario (YYYY-MM-DD), una sola vez por negocio. */
    private LocalDate date;

    /**
     * true: la excepción se repite todos los años en el día y mes de
     * {@code date} (Navidad es 25/12 todos los años); false: sólo esa fecha
     * exacta. El año de {@code date} es sólo la referencia para la validación,
     * nunca parte del match anual. En el PUT, ausente significa false — un
     * payload viejo conserva exactamente su significado. Una fecha exacta gana
     * sobre una anual que caiga el mismo día: por eso una anual y una one-off
     * que compartan día/mes están permitidas.
     */
    private boolean yearly;

    /**
     * Ordered shifts of the date (sorted by {@code opensAt}); empty when the
     * date is closed (feriado). Required on a date entry present in a request.
     */
    private List<BusinessShiftDTO> shifts;

}
