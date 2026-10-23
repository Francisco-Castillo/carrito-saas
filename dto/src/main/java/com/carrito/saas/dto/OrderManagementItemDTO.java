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

	/** Preferencias del cliente ("sin mayonesa"): la consola las muestra
	 * junto al ítem en el histórico del día. */
	private String notes;

	private BigDecimal unitPrice;

	private BigDecimal subtotal;

}
