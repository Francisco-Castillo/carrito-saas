package com.carrito.saas.service.interfaces;

import java.util.List;
import java.util.Map;

import com.carrito.saas.dto.ProductTrendDTO;




public interface IProductTrendService {
	
	//Map<Long, Double> getTrends(Long businessId, List<Long> productIds);
	
	Map<Long, ProductTrendDTO> getTrends(Long businessId, List<Long> productIds);
	
	List<ProductTrendDTO> getTopUp(int limit);
	
	List<ProductTrendDTO> getTopDown(int limit);

}
