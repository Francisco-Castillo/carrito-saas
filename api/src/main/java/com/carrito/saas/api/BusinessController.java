package com.carrito.saas.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.BusinessDTO;
import com.carrito.saas.dto.OrderKitchenDTO;
import com.carrito.saas.service.interfaces.IBusinessService;
import com.carrito.saas.service.interfaces.IOrderService;

@RestController
@RequestMapping("/api/business")
public class BusinessController {

	private IOrderService orderService;
	private IBusinessService iBusinessService;

	public BusinessController(IOrderService orderService) {
		super();
		this.orderService = orderService;
	}

	@GetMapping("/orders/active")
	public List<OrderKitchenDTO> getActiveOrders() {
		return orderService.getActiveOrders();
	}

	@GetMapping("/slug/{slug}")
	public BusinessDTO getBySlug(@PathVariable String slug) {
		return iBusinessService.getBusinessBySlug(slug);
	}

}
