package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.reportes.SalesByHourDTO;
import com.carrito.saas.dto.reportes.TopProductDTO;

public interface IAnalyticsService {
	
	List<TopProductDTO> getTopProductsToday(String slug);
	List<SalesByHourDTO> getSalesByHourToday(String slug);

}
