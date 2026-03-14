package com.carrito.saas.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.reportes.SalesByHourDTO;
import com.carrito.saas.dto.reportes.TopProductDTO;
import com.carrito.saas.service.interfaces.IAnalyticsService;

@RestController
@RequestMapping("/api/restaurants/{slug}/analytics")
public class AnalyticsController {

	private final IAnalyticsService analyticsService;

	public AnalyticsController(IAnalyticsService analyticsService) {
		super();
		this.analyticsService = analyticsService;
	}

	@GetMapping("/top-products")
	public List<TopProductDTO> getTopProducts(@PathVariable String slug) {
		return analyticsService.getTopProductsToday(slug);
	}

	@GetMapping("/sales-by-hour")
	public List<SalesByHourDTO> getSalesByHour(@PathVariable String slug) {
		return analyticsService.getSalesByHourToday(slug);
	}

}
