package com.carrito.saas.service.interfaces;

import java.util.List;
import java.util.Map;



public interface IProductTrendService {
	
	Map<Long, Double> getTrends(Long businessId, List<Long> productIds);

}
