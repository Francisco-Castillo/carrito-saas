package com.carrito.saas.dto;

import java.math.BigDecimal;

import lombok.Data;

/**
 * Ítem del pedido para la pantalla de gestión: incluye precio unitario y
 * subtotal, que el ítem del KDS ({@link OrderItemKitchenDTO}) no trae.
 */
@Data
public class OrderManagementItemDTO {

	private String name;

	private Integer quantity;

	private BigDecimal unitPrice;

	private BigDecimal subtotal;

}
