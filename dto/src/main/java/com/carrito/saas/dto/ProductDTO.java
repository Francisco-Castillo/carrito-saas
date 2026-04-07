package com.carrito.saas.dto;

import java.math.BigDecimal;

import com.carrito.saas.repository.enums.UnitMeasure;

import lombok.Data;

@Data
public class ProductDTO {
	
	private Long id;

    private String name;
    
    private Double trendPercentage;

    private String description;

    private BigDecimal price;
    private BigDecimal cost;

    private Boolean active;
    
    private Integer stock;

    private Long categoryId;
    
    private String categoryName;
    
    private String imageUrl;
    
    private UnitMeasure unitMeasure;

	public ProductDTO() {
		super();
	}
    
    
}
