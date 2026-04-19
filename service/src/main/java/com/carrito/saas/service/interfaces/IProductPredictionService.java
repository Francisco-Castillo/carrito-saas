package com.carrito.saas.service.interfaces;

import java.util.List;
import java.util.Map;

import com.carrito.saas.dto.ProductPredictionDTO;

public interface IProductPredictionService {
	
	 Map<Long, ProductPredictionDTO> getPredictions(Long businessId, List<Long> productIds);

}
