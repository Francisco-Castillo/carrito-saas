package com.carrito.saas.service.impl;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.ProductInsight;

@Component
public class ProductInsightService {
	public ProductInsight calculate(Double trend, Double prediction) {

		if (trend == null || prediction == null) {
			return ProductInsight.NEUTRAL;
		}

		// CRÍTICO: caída fuerte y sin recuperación
		if (trend < 0 && prediction <= 0) {
			return ProductInsight.CRITICAL;
		}

		// OPORTUNIDAD: cae pero podría recuperarse
		if (trend < 0 && prediction > 0) {
			return ProductInsight.OPPORTUNITY;
		}

		//  BOOST: todo positivo
		if (trend > 0 && prediction > 0) {
			return ProductInsight.BOOST;
		}

		//  INESTABLE: sube pero se espera caída
		if (trend > 0 && prediction <= 0) {
			return ProductInsight.UNSTABLE;
		}

		return ProductInsight.NEUTRAL;
	}
}
