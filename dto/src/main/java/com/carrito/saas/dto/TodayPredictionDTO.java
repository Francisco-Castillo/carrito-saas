package com.carrito.saas.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TodayPredictionDTO {
	
	private Integer predictedOrders;
    private Integer peakHour;
    private Long topProductId;
    private String topProductName;
    private Double avgOrders;
    private Double variationPercent;
    private String demandLevel;

}
