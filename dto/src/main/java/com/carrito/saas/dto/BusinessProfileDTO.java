package com.carrito.saas.dto;

import java.util.List;

import com.carrito.saas.dto.DateExceptionDTO;

import lombok.Data;

/**
 * Owner-facing storefront profile ({@code GET}/{@code PUT
 * /api/business/profile}). The fields mean the same as on
 * {@link BusinessDTO}; {@code hours} and {@code dateExceptions} are replaced
 * wholesale on PUT (a day missing from the list is deleted; a date missing
 * from {@code dateExceptions} is a normal day again). On the request side
 * {@code dateExceptions} is mandatory (400 when absent): like the
 * {@code shifts} guard on a day, a client forgetting the field must not
 * silently erase the owner's holidays — an empty list is the explicit "no
 * special dates".
 */
@Data
public class BusinessProfileDTO {

    private String name;

    private String logoUrl;

    private String bannerUrl;

    private String address;

    private String instagramUrl;

    private List<BusinessHoursDTO> hours;

    /** Fechas especiales, ordenadas por fecha; vacío cuando no hay ninguna. */
    private List<DateExceptionDTO> dateExceptions;

}
