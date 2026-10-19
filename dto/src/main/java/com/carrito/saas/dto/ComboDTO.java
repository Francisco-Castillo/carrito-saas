package com.carrito.saas.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

@Data
public class ComboDTO {
	
	private Long id;

    private String name;

    private BigDecimal price;

    private Long categoryId;

    /** El panel necesita el estado para el switch y la carta para saber qué
     * combos están activos; aditivo e inofensivo para los clientes existentes. */
    private Boolean active;

    private List<ComboItemDTO> items; // opcional (para detalle)

    /** Imagen propia del combo (opcional); el menú cae al derivado por componentes si es null. */
    private String imageUrl;

}
