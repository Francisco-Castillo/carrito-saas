package com.carrito.saas.service.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.carrito.saas.dto.ProductTrendDTO;
import com.carrito.saas.repository.entity.ProductTrend;
import com.carrito.saas.repository.jdbc.ProductTrendRepository;
import com.carrito.saas.security.SecurityService;
import com.carrito.saas.service.interfaces.IProductTrendService;
import com.carrito.saas.service.mapper.interfaces.IProductTrendMapper;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ProductTrendServiceImpl implements IProductTrendService {

	private final ProductTrendRepository repository;
	private final IProductTrendMapper productTrendMapper;
	private final SecurityService securityService;
	
	
	

	public ProductTrendServiceImpl(ProductTrendRepository repository, IProductTrendMapper productTrendMapper,
			SecurityService securityService) {
		super();
		this.repository = repository;
		this.productTrendMapper = productTrendMapper;
		this.securityService = securityService;
	}

	@Override
	@Cacheable(
		    value = "productTrends",
		    key = "#businessId + '-' + #root.target.generateKey(#productIds)"
		)
	public Map<Long, ProductTrendDTO> getTrends(Long businessId, List<Long> productIds) {
		log.info("EJECUTANDO QUERY A DB para trends (NO cache)");

	    Map<Long, ProductTrend> entityMap =
	            repository.findMapByProductIds(businessId, productIds);

	    if (entityMap.isEmpty()) {
	        return Collections.emptyMap();
	    }

	    Map<Long, ProductTrendDTO> result = new HashMap<>(entityMap.size());

	    for (Map.Entry<Long, ProductTrend> entry : entityMap.entrySet()) {
	        result.put(entry.getKey(), productTrendMapper.toDTO(entry.getValue()));
	    }

	    return result;
	}
	
	@Cacheable(
		    value = "topUp",
		    key = "@tenantKey.generate(#limit)"
		)
	public List<ProductTrendDTO> getTopUp(int limit) {
		Long businessId = securityService.getCurrentBusinessId();
		return repository.findTopUp(businessId, limit)
                .stream()
                .map(productTrendMapper::toDTO)
                .toList();
	}

	@Cacheable(
		    value = "topDown",
		    key = "@tenantKey.generate(#limit)"
		)
	public List<ProductTrendDTO> getTopDown(int limit) {
		Long businessId = securityService.getCurrentBusinessId();
		return repository.findTopDown(businessId, limit)
                .stream()
                .map(productTrendMapper::toDTO)
                .toList();
	}
	
	public String generateKey(List<Long> productIds) {
	    return productIds.stream()
	            .sorted()
	            .map(String::valueOf)
	            .collect(Collectors.joining(","));
	}



}








