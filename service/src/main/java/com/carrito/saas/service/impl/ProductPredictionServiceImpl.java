package com.carrito.saas.service.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.carrito.saas.dto.ProductPredictionDTO;
import com.carrito.saas.repository.entity.ProductPrediction;
import com.carrito.saas.repository.jdbc.ProductPredictionRepository;
import com.carrito.saas.service.interfaces.IProductPredictionService;

@Service
public class ProductPredictionServiceImpl implements IProductPredictionService {
	private final ProductPredictionRepository repository;

	public ProductPredictionServiceImpl(ProductPredictionRepository repository) {
		this.repository = repository;
	}

	@Override
	@Cacheable(
		    value = "productPredictions",
		    key = "#businessId + '-' + #root.target.generateKey(#productIds)"
		)
	public Map<Long, ProductPredictionDTO> getPredictions(Long businessId, List<Long> productIds) {
		Map<Long, ProductPrediction> entities = repository.findMapByProductIds(businessId, productIds);

		if (entities.isEmpty()) {
			return Collections.emptyMap();
		}

		Map<Long, ProductPredictionDTO> result = new HashMap<>(entities.size());

		for (Map.Entry<Long, ProductPrediction> entry : entities.entrySet()) {

			ProductPrediction e = entry.getValue();

			ProductPredictionDTO dto = ProductPredictionDTO.builder().productId(e.getProductId())
					.predictedGrowth(e.getPredictedGrowth()).build();

			result.put(entry.getKey(), dto);
		}

		return result;
	}
	
	public String generateKey(List<Long> productIds) {
	    return productIds.stream()
	            .sorted()
	            .map(String::valueOf)
	            .collect(Collectors.joining(","));
	}

}
