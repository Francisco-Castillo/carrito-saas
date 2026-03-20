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

    private List<ComboItemDTO> items; // opcional (para detalle)

}
