package com.carrito.saas.dto;

import java.time.LocalTime;

import lombok.Data;

/**
 * One ordered shift of a weekday's opening hours. {@code closesAt} earlier
 * than {@code opensAt} means the shift crosses midnight and ends in the early
 * hours of the next day (a Friday 11:00 → 00:30 closes at 00:30 the following
 * Saturday). The ordinal inside the day is implicit: the server sorts the
 * shifts of a day by {@code opensAt} and never exposes the position.
 */
@Data
public class BusinessShiftDTO {

    private LocalTime opensAt;

    private LocalTime closesAt;

}
