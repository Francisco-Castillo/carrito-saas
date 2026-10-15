package com.carrito.saas.dto;

import java.util.List;

import lombok.Data;

/**
 * Owner-facing storefront profile ({@code GET}/{@code PUT
 * /api/business/profile}). The fields mean the same as on
 * {@link BusinessDTO}; {@code hours} is replaced wholesale on PUT (a day
 * missing from the list is deleted).
 */
@Data
public class BusinessProfileDTO {

    private String name;

    private String logoUrl;

    private String bannerUrl;

    private String address;

    private String instagramUrl;

    private List<BusinessHoursDTO> hours;

}
