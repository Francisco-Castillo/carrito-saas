package com.carrito.saas.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class ProductOptionDTO {

	private Long id;
	private String name;
	private BigDecimal priceDelta; // recargo sobre el precio del producto
}
