package com.carrito.saas.dto;

import lombok.Data;

/**
 * Mesa asignada al pedido (solo SALON). {@code name} es el nombre de la mesa
 * y {@code number} su número; la mesa va solo cuando el pedido la tiene.
 */
@Data
public class OrderManagementTableDTO {

	private Long id;

	private String name;

	private Integer number;

}
