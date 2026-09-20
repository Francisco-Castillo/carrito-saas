package com.carrito.saas.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.CancelOrderRequestDTO;
import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.service.interfaces.IOrderService;

@RestController
@RequestMapping("/api/orders")

public class OrderController {

	private final IOrderService orderService;
	private final OrderAnnouncer announcer;

	public OrderController(IOrderService orderService, OrderAnnouncer announcer) {
		super();
		this.orderService = orderService;
		this.announcer = announcer;
	}

	@PostMapping("/menu/{slug}")
	public OrderDTO createOrder(@PathVariable String slug, @RequestBody OrderRequestDTO request) {
		OrderDTO order = orderService.createOrder(slug, request);

		// Announced AFTER the transactional writer returns — never inside the
		// transaction (an order announced inside it could be erased from the
		// kitchen by a later rollback). The broadcast + metric now live in ONE
		// place: OrderAnnouncer (T7a.2, open gap 54).
		announcer.orderCreated(order);

		return order;
	}

	@PatchMapping("/{orderId}/status")
	public ResponseEntity<Order> updateStatus(@PathVariable Long orderId, @RequestParam OrderStatus status) {

		OrderDTO order = orderService.updateStatus(orderId, status);

		announcer.orderChanged(order);

		return ResponseEntity.ok().build();
	}

	@PatchMapping("/{id}/cancel")
	public ResponseEntity<Void> cancelOrder(@PathVariable Long id, @RequestBody CancelOrderRequestDTO request) {

		OrderDTO order = orderService.cancelOrder(id, request.getReasonId(), request.getNote());

		announcer.orderChanged(order);

		return ResponseEntity.ok().build();
	}
}
