package com.carrito.saas.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.ConfirmProposalRequestDTO;
import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderProposalDTO;
import com.carrito.saas.security.ISecurityService;
import com.carrito.saas.service.interfaces.IOrderProposalService;

import jakarta.validation.Valid;

/**
 * Operator-facing HTTP surface for order proposals (T7a of
 * {@code odd/tasks/whatsapp-inbound.md}): LIST the pending ones, REJECT one,
 * CONFIRM one (T7a.2).
 *
 * <p>The paths fall under {@code /api/business/**}, which the existing
 * {@code .anyRequest().authenticated()} already protects — no SecurityConfig
 * change, and an anonymous request is rejected by the filter chain before it
 * reaches this controller.</p>
 *
 * <p>{@code businessId} comes ONLY from the JWT
 * ({@code ISecurityService#getCurrentBusinessId()}), never from a parameter:
 * a business can only ever see and decide its own proposals.</p>
 */
@RestController
@RequestMapping("/api/business/proposals")
public class OrderProposalController {

	private final IOrderProposalService orderProposalService;
	private final ISecurityService securityService;
	private final OrderAnnouncer announcer;

	public OrderProposalController(IOrderProposalService orderProposalService, ISecurityService securityService,
			OrderAnnouncer announcer) {
		this.orderProposalService = orderProposalService;
		this.securityService = securityService;
		this.announcer = announcer;
	}

	@GetMapping
	public List<OrderProposalDTO> listPending() {

		return orderProposalService.listPendingProposals(securityService.getCurrentBusinessId());
	}

	@PostMapping("/{id}/reject")
	public OrderProposalDTO reject(@PathVariable Long id) {

		return orderProposalService.rejectProposal(id, securityService.getCurrentBusinessId());
	}

	/**
	 * Confirms a proposal: the transactional service core creates the order
	 * through {@code createOrder}; the announcement happens HERE, AFTER the
	 * service returns — never inside the transaction (an order announced
	 * inside it could be erased from the kitchen by a later rollback).
	 */
	@PostMapping("/{id}/confirm")
	public OrderDTO confirm(@PathVariable Long id, @Valid @RequestBody ConfirmProposalRequestDTO request) {

		OrderDTO order = orderProposalService.confirmProposal(id, securityService.getCurrentBusinessId(), request);
		announcer.orderCreated(order);
		return order;
	}
}
