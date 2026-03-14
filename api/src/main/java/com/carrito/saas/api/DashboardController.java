package com.carrito.saas.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.reportes.DashboardTodayDTO;
import com.carrito.saas.dto.reportes.OrderStatusSummaryDTO;
import com.carrito.saas.service.interfaces.IDashboardService;

@RestController
@RequestMapping("/api/restaurants")
public class DashboardController {

	private final IDashboardService dashboardService;

	public DashboardController(IDashboardService dashboardService) {
		super();
		this.dashboardService = dashboardService;
	}

	@GetMapping("/{slug}/dashboard/today")
	public DashboardTodayDTO getToday(@PathVariable String slug) {
		return dashboardService.getTodayStats(slug);
	}
	
	@GetMapping("/{slug}/orders/status-summary")
	public OrderStatusSummaryDTO getStatusSummary(@PathVariable String slug) {
		return dashboardService.getOrderStatusSummary(slug);
	}

}
