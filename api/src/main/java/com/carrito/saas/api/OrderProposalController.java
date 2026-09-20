package com.carrito.saas.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.OrderProposalDTO;
import com.carrito.saas.security.ISecurityService;
import com.carrito.saas.service.interfaces.IOrderProposalService;

/**
 * Operator-facing HTTP surface for order proposals (T7a.1 of
 * {@code odd/tasks/whatsapp-inbound.md}): LIST the pending ones and REJECT
 * one. Confirmation is T7a.2 and lands here later.
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

	public OrderProposalController(IOrderProposalService orderProposalService, ISecurityService securityService) {
		this.orderProposalService = orderProposalService;
		this.securityService = securityService;
	}

	@GetMapping
	public List<OrderProposalDTO> listPending() {

		return orderProposalService.listPendingProposals(securityService.getCurrentBusinessId());
	}

	@PostMapping("/{id}/reject")
	public OrderProposalDTO reject(@PathVariable Long id) {

		return orderProposalService.rejectProposal(id, securityService.getCurrentBusinessId());
	}
}
