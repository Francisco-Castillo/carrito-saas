package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.OrderProposalDTO;

/**
 * Operator-facing read and discard of WhatsApp order proposals (T7a.1 of
 * {@code odd/tasks/whatsapp-inbound.md}). Confirmation is T7a.2 and lands on
 * this interface later.
 *
 * <p>Every method is business-scoped: {@code businessId} comes ONLY from the
 * JWT, never from a request parameter.</p>
 */
public interface IOrderProposalService {

	/**
	 * The PENDING proposals of one business, newest first (received_at DESC,
	 * NULL last), with their interpreted lines.
	 */
	List<OrderProposalDTO> listPendingProposals(Long businessId);

	/**
	 * Discards a proposal: PENDING → REJECTED. Creates no order, touches no
	 * stock, deletes no line. Not found or foreign → 404; already decided →
	 * 409.
	 */
	OrderProposalDTO rejectProposal(Long proposalId, Long businessId);

}
