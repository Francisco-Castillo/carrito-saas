package com.carrito.saas.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class ComboItemDTO {

	/**
	 * Id del producto: el panel de admin prellena el picker de edición por id
	 * (dos productos pueden compartir nombre; el cruce por nombre era frágil).
	 */
	private Long productId;

	private String productName;

    private BigDecimal quantity;

}
