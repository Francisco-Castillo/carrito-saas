package com.carrito.saas.repository.entity;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductPrediction {

	private Long productId;
	private Long businessId;
	private Double predictedGrowth; // % esperado
	private LocalDateTime predictionDate;

}
