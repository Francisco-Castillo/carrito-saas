package com.carrito.saas.dto;

import java.math.BigDecimal;

import lombok.Data;

/**
 * Una opción elegida sobre un ítem del pedido (odd/tasks/product-options.md
 * S4/S5). Se resuelve en el servidor: el request manda ids
 * (OrderItemDTO.selectedOptionIds) y la respuesta lleva nombres y recargos
 * ya resueltos contra los grupos del producto.
 */
@Data
public class SelectedOptionDTO {

	private Long optionId;
	private String groupName;
	private String optionName;
	private BigDecimal priceDelta;
}
