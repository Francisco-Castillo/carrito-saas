package com.carrito.saas.dto;

import java.math.BigDecimal;

import com.carrito.saas.repository.enums.UnitMeasure;

import lombok.Data;

@Data
public class ProductCreateDTO {
	
	private Long categoryId;
	private String name;
	private String description;
	private BigDecimal price; // precio de venta
	private BigDecimal cost; // Costo.
	private Integer stock;
	private Boolean active;
	private UnitMeasure unitMeasure; // Puede ser unidad, Porcion, Kilos, etc.
	

}
