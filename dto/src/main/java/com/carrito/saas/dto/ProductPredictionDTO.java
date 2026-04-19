package com.carrito.saas.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Builder
@Getter
@Setter
public class ProductPredictionDTO {
	
	 private Long productId;
	    private Double predictedGrowth; // % esperado (ej: +12.5, -8.2)

}
