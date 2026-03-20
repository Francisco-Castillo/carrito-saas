package com.carrito.saas.dto;

import java.math.BigDecimal;

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

}
