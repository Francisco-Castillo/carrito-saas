package com.carrito.saas.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.carrito.saas.repository.jdbc.ProductTrendRepository;
import com.carrito.saas.service.interfaces.IProductTrendService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductTrendServiceImpl implements IProductTrendService {

	private final ProductTrendRepository repository;

	@Override
	@Cacheable(value = "product-trends", key = "#businessId")
	public Map<Long, Double> getTrends(Long businessId, List<Long> productIds) {
		 log.info("EJECUTANDO QUERY A DB para trends (NO cache)");
		return repository.getTrends(businessId, productIds);
	}

}
