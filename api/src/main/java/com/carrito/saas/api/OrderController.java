package com.carrito.saas.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.dto.OrderResponseDTO;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.service.interfaces.IOrderService;

@RestController
@RequestMapping("/api/orders")

public class OrderController {

	private final IOrderService orderService;

	public OrderController(IOrderService orderService) {
		super();
		this.orderService = orderService;
	}

	@PostMapping
	public OrderResponseDTO createOrder(@RequestBody OrderRequestDTO request) {
		return orderService.createOrder(request);
	}

	@PatchMapping("/{orderId}/status")
	public ResponseEntity<Order> updateStatus(@PathVariable Long orderId, @RequestParam OrderStatus status) {

		orderService.updateStatus(orderId, status);

		return ResponseEntity.ok().build();
	}
}
