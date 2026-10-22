package com.carrito.saas.service.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.carrito.saas.dto.ProductTrendDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.ProductTrend;
import com.carrito.saas.repository.jpa.BusinessRepository;
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
	private final BusinessRepository businessRepository;
	
	
	

	public ProductTrendServiceImpl(ProductTrendRepository repository, IProductTrendMapper productTrendMapper,
			SecurityService securityService, BusinessRepository businessRepository) {
		super();
		this.repository = repository;
		this.productTrendMapper = productTrendMapper;
		this.securityService = securityService;
		this.businessRepository = businessRepository;
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
	
	@Override
	public List<ProductTrendDTO> getTopUp(String slug, int limit) {
		return repository.findTopUp(resolveAuthorizedBusinessId(slug), limit)
				.stream()
				.map(productTrendMapper::toDTO)
				.toList();
	}
	
	@Override
	public List<ProductTrendDTO> getTopDown(String slug, int limit) {
		return repository.findTopDown(resolveAuthorizedBusinessId(slug), limit)
				.stream()
				.map(productTrendMapper::toDTO)
				.toList();
	}
	
	/**
	 * The slug in the path is not a tenant selector by itself: it only names
	 * the business whose data is requested. The caller's own business (from
	 * the JWT) must match it — an unknown slug and a foreign slug answer the
	 * same 404, so the route does not reveal which slugs exist. Deliberately
	 * not cached: the legacy global variants cache per caller, but a slug-keyed
	 * cache would hand one tenant's rows to whichever caller asked first.
	 */
	private Long resolveAuthorizedBusinessId(String slug) {
		Long businessId = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND))
				.getId();
		Long callerBusinessId = securityService.getCurrentBusinessId();
		if (callerBusinessId == null || !callerBusinessId.equals(businessId)) {
			throw new BusinessException("Local no encontrado", ErrorType.NOT_FOUND);
		}
		return businessId;
	}
	
	public String generateKey(List<Long> productIds) {
	    return productIds.stream()
	            .sorted()
	            .map(String::valueOf)
	            .collect(Collectors.joining(","));
	}



}








