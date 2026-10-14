package com.carrito.saas.dto;

import java.util.List;

import lombok.Data;

/**
 * Public business identity on the menu payload. The storefront fields and
 * {@code hours} (ordered Monday→Sunday, empty when the business has none) are
 * filled by {@code MenuServiceImpl}; {@code whatsappNumber} keeps its
 * pre-existing meaning (canonical E.164 digits or NULL).
 */
@Data
public class BusinessDTO {
	
	private Long id;

    private String name;

    private String slug;

    private String whatsappNumber;

    private String logoUrl;

    private String bannerUrl;

    private String address;

    private String instagramUrl;

    private List<BusinessHoursDTO> hours;

}
