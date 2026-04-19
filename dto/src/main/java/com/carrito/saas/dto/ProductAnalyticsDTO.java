package com.carrito.saas.dto;

import lombok.Builder;

@Builder
public class ProductAnalyticsDTO {
	
	private Long productId;
    private Double trendPercentage;
    private String trendDirection;
    private ProductInsight insight;

}
