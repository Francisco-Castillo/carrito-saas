package com.carrito.saas.dto;

import lombok.Data;

/**
 * Motivo de cancelación del pedido (presente solo cuando el pedido fue
 * cancelado con motivo).
 */
@Data
public class OrderManagementCancellationReasonDTO {

	private Long id;

	private String description;

}
