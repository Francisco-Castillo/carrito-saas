package com.carrito.saas.api;

import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.service.interfaces.IOrderService;

@RestController
@RequestMapping("/api/orders")

public class OrderController {

	private final IOrderService orderService;
	private final SimpMessagingTemplate messagingTemplate;

	public OrderController(IOrderService orderService, SimpMessagingTemplate messagingTemplate) {
		super();
		this.orderService = orderService;
		this.messagingTemplate = messagingTemplate;
	}

	@PostMapping("/menu/{slug}")
	public OrderDTO createOrder(@PathVariable String slug, @RequestBody OrderRequestDTO request) {
		OrderDTO order = orderService.createOrder(slug, request);

	    messagingTemplate.convertAndSend(
	            "/topic/orders/"+ order.getBusinessSlug(),
	            order
	    );

	    return order;
	}

	@PatchMapping("/{orderId}/status")
	public ResponseEntity<Order> updateStatus(@PathVariable Long orderId, @RequestParam OrderStatus status) {

		OrderDTO order = orderService.updateStatus(orderId, status);

		 messagingTemplate.convertAndSend(
		            "/topic/orders/"+ order.getBusinessSlug(),
		            order);

		return ResponseEntity.ok().build();
	}
}
