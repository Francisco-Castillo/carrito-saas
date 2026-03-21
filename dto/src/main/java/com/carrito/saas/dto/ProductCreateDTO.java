package com.carrito.saas.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class ProductCreateDTO {
	
	private String name;
	private String description;
	private BigDecimal price; // precio de venta
	private BigDecimal cost; // Costo.
	private Integer stock;
	private Long categoryId;

}
