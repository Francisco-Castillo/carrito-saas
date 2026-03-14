package com.carrito.saas.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.OrderKitchenDTO;
import com.carrito.saas.service.interfaces.IOrderService;

@RestController
@RequestMapping("/api/business")
public class BusinessController {

	private IOrderService orderService;

	public BusinessController(IOrderService orderService) {
		super();
		this.orderService = orderService;
	}

	/*
	@GetMapping("/{businessId}/orders/active")
	public List<OrderKitchenDTO> getActiveOrders(@PathVariable Long businessId) {
		return orderService.getActiveOrders(businessId);
	}*/
	
	@GetMapping("/{slug}/orders/active")
	public List<OrderKitchenDTO> getActiveOrdersBySlug(@PathVariable String slug) {
		return orderService.getActiveOrdersBySlug(slug);
	}

}
