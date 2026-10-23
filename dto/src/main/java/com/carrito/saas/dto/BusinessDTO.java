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

    /** Perfil de TikTok del local (icono en la carta). */
    private String tiktokUrl;

    /** Brand color del local como hex {@code #RRGGBB}, o null si no cargó uno. */
    private String primaryColor;

    /** Tipografía de la carta: slug de la lista blanca de fuentes curadas,
     *  o null si el dueño no eligió ninguna (la carta usa Inter). */
    private String menuFont;

    private List<BusinessHoursDTO> hours;

    /** Fechas especiales (feriados y horarios especiales), ordenadas por fecha;
     *  vacío cuando el negocio no tiene ninguna. La completa MenuServiceImpl. */
    private List<DateExceptionDTO> dateExceptions;

}
