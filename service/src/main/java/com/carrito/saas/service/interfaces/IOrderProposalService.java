package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.ConfirmProposalRequestDTO;
import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderProposalDTO;

/**
 * Operator-facing read and discard of WhatsApp order proposals (T7a.1 of
 * {@code odd/tasks/whatsapp-inbound.md}), plus the confirmation core
 * (T7a.2).
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

	/**
	 * Confirms a proposal: PENDING → CONFIRMED with the resulting order
	 * created by {@code createOrder}, the SINGLE writer (no stock, price,
	 * orderNumber or broadcast logic is ever duplicated here).
	 *
	 * <p>One transaction, in this exact order: ownership-scoped read (absent,
	 * foreign, or FAILED-with-no-business → 404); status must be PENDING
	 * (→ 409); the single colocability gate
	 * {@code OrderProposalServiceImpl#isConfirmable} (any non-RESOLVED line
	 * blocks the whole proposal → 409, nothing touches stock before it);
	 * operator-data validation and {@code OrderRequestDTO} assembly BEFORE the
	 * CAS (the bulk update clears the persistence context); the CAS claim
	 * PENDING → CONFIRMED scoped by id AND business AND status (0 rows → 409
	 * — the loser of a race creates nothing); only then {@code createOrder}
	 * with the slug taken from the proposal's own business; finally the created
	 * order id is recorded on the proposal with a second bulk update.
	 *
	 * <p>The whole method is ONE transaction: if {@code createOrder} fails
	 * (insufficient stock, missing product), the CAS rolls back and the
	 * proposal is PENDING again — retryable. That is what makes "stock
	 * decremented once" true in both directions.</p>
	 *
	 * @return the created order as produced by {@code createOrder}
	 */
	OrderDTO confirmProposal(Long proposalId, Long businessId, ConfirmProposalRequestDTO request);

}
