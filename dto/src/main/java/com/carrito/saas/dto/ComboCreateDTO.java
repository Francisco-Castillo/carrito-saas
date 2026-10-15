package com.carrito.saas.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

@Data
public class ComboCreateDTO {
	
	private String name;
	private BigDecimal price;

	/**
	 * Categoría del combo. Debe existir y pertenecer al negocio del
	 * llamador (businessId anclado al JWT); un id ajeno o inexistente
	 * resuelve como 404.
	 */
	private Long categoryId;

	private List<ComboProductDTO> products;

}
