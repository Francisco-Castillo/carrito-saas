package com.carrito.saas.service.interfaces;

import java.util.List;
import java.util.Map;

import com.carrito.saas.dto.ProductTrendDTO;




public interface IProductTrendService {
	
	//Map<Long, Double> getTrends(Long businessId, List<Long> productIds);
	
	Map<Long, ProductTrendDTO> getTrends(Long businessId, List<Long> productIds);
	
	List<ProductTrendDTO> getTopUp(int limit);
	
	List<ProductTrendDTO> getTopDown(int limit);

	/**
	 * Slug-scoped variants used by the panel API
	 * ({@code GET /api/restaurants/{slug}/trends/top-up|top-down}). The slug is
	 * the authoritative tenant selector — an unknown slug is a 404, and only
	 * that business's rows are returned.
	 */
	List<ProductTrendDTO> getTopUp(String slug, int limit);
	
	List<ProductTrendDTO> getTopDown(String slug, int limit);

}
