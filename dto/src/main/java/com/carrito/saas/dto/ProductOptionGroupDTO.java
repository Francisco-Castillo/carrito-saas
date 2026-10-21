package com.carrito.saas.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Grupo de opciones de un producto (odd/tasks/product-options.md S1/S2).
 *
 * required=true → el cliente debe elegir antes de agregar al carrito.
 * multiple=false → elige exactamente 1 opción.
 * multiple=true  → elige entre minSelections y maxSelections (null = sin tope).
 */
@Data
public class ProductOptionGroupDTO {

	private Long id;
	private String name;
	private Boolean required;
	private Boolean multiple;
	private Integer minSelections;
	private Integer maxSelections;
	private List<ProductOptionDTO> options = new ArrayList<>();
}
