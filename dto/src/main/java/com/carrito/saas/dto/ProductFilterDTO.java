package com.carrito.saas.dto;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class ProductFilterDTO {
	
	private String search;
    private Long categoryId;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;

}
