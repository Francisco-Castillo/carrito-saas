package com.carrito.saas.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Ítem del pedido para la pantalla de gestión: incluye precio unitario y
 * subtotal, que el ítem del KDS ({@link OrderItemKitchenDTO}) no trae, más
 * las opciones elegidas con su recargo resuelto (snapshot al momento de la
 * venta, que el modal de detalle muestra bajo cada ítem). Vacío cuando el
 * producto no tiene opciones elegidas.
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

	/**
	 * Opciones elegidas del ítem (mismo contrato que
	 * {@link OrderItemKitchenDTO#selectedOptions} pero con el recargo, que el
	 * detalle de gestión sí muestra): grupo, nombre y priceDelta resueltos
	 * contra los grupos del producto. Nunca null.
	 */
	private List<SelectedOptionDTO> selectedOptions = new ArrayList<>();

}
