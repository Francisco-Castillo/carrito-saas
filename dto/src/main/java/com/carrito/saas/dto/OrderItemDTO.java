package com.carrito.saas.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class OrderItemDTO {

	private Long productId;

	private Long comboId;
	private Boolean comboRoot;

	private String productName;

	private Integer quantity;
	private BigDecimal price; // precio unitario en el momento de la compra
	private BigDecimal subtotal; // price * quantity
	/**
	 * Costo del producto al momento de la venta
	 */
	private BigDecimal cost;

	/**
	 * Opciones elegidas (request): ids de ProductOption, resueltos y
	 * validados por el servicio contra los grupos del producto
	 * (odd/tasks/product-options.md S4).
	 */
	private List<Long> selectedOptionIds;

	/**
	 * Opciones elegidas (respuesta): resueltas con grupo, nombre y recargo
	 * (S5: llegan al KDS y al historial de pedidos).
	 */
	private List<SelectedOptionDTO> selectedOptions;

}
