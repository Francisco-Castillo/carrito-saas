package com.carrito.saas.service.interfaces;

import com.carrito.saas.dto.reportes.DashboardTodayDTO;
import com.carrito.saas.dto.reportes.OrderStatusSummaryDTO;

public interface IDashboardService {
	
	DashboardTodayDTO getTodayStats(String slug);
	OrderStatusSummaryDTO getOrderStatusSummary(String slug);

}
