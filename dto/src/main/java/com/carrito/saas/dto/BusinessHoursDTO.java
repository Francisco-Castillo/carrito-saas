package com.carrito.saas.dto;

import java.time.LocalTime;

import lombok.Data;

/**
 * One weekday of opening hours in the public menu and owner profile payloads.
 * When {@code closed} is {@code true} the times are null.
 */
@Data
public class BusinessHoursDTO {

    /** ISO day: 1 = Monday … 7 = Sunday. */
    private Integer dayOfWeek;

    private LocalTime opensAt;

    private LocalTime closesAt;

    private Boolean closed;

}
