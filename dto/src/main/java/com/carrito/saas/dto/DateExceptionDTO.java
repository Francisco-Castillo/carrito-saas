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
     * Ordered shifts of the date (sorted by {@code opensAt}); empty when the
     * date is closed (feriado). Required on a date entry present in a request.
     */
    private List<BusinessShiftDTO> shifts;

}
