package com.carrito.saas.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.AverageOrdersDTO;
import com.carrito.saas.dto.reportes.DashboardTodayDTO;
import com.carrito.saas.dto.reportes.OrderStatusSummaryDTO;
import com.carrito.saas.service.interfaces.IDashboardService;
import com.carrito.saas.service.interfaces.IPredictionService;

@RestController
@RequestMapping("/api/restaurants")
public class DashboardController {

	private final IDashboardService dashboardService;
	private final IPredictionService predictionService;

	public DashboardController(IDashboardService dashboardService, IPredictionService predictionService) {
		this.dashboardService = dashboardService;
		this.predictionService = predictionService;
	}

	@GetMapping("/{slug}/dashboard/today")
	public DashboardTodayDTO getToday(@PathVariable String slug) {
		return dashboardService.getTodayStats(slug);
	}

	@GetMapping("/dashboard/average-orders")
	public AverageOrdersDTO getAverageOrders() {
		Double avg = predictionService.getAverageOrders();
		return new AverageOrdersDTO(avg);
	}

	@GetMapping("/{slug}/orders/status-summary")
	public OrderStatusSummaryDTO getStatusSummary(@PathVariable String slug) {
		return dashboardService.getOrderStatusSummary(slug);
	}

}
