package com.carrito.saas.repository.entity;

import java.time.LocalDateTime;

import com.carrito.saas.repository.enums.TrendDirection;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductTrend {
	
	private Long productId;
	private String productName;
    private Long businessId;

    private Double trendPercentage;
    private Integer currentWeekSales;
    private Integer previousWeekSales;

    private TrendDirection trendDirection;
    private LocalDateTime updatedAt;

}
