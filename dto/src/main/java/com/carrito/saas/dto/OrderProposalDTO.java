package com.carrito.saas.dto;

import java.time.Instant;
import java.util.List;

import com.carrito.saas.repository.enums.ProposalStatus;

import lombok.Data;

/**
 * Operator-facing view of an inbound WhatsApp order proposal (T7a.1 of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p>{@code confirmable} is computed by the SAME single rule that will gate
 * the confirmation endpoint (T7a.2) — never a second opinion: the operator
 * view must not reimplement the rule in JavaScript.</p>
 */
@Data
public class OrderProposalDTO {

	private Long id;

	private String channel;

	private String fromPhone;

	private String customerName;

	private String rawText;

	private Instant receivedAt;

	private ProposalStatus status;

	private String failureReason;

	/**
	 * Whether the proposal can be confirmed as-is: a business, at least one
	 * line, every line RESOLVED, and every RESOLVED line carrying exactly one
	 * of productId/comboId.
	 */
	private boolean confirmable;

	private List<OrderProposalItemDTO> items;

}
