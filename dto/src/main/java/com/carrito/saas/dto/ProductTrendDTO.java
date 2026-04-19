package com.carrito.saas.dto;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class ProductTrendDTO {
	
	
	private Long productId;
	private String productName;
    private Double trendPercentage;
    private String trendDirection;
    //private ProductInsight insight; no es necesario en /api/trends/top-down, Revisar en donde repercute.

}
