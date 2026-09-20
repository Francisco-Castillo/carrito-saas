package com.carrito.saas.dto;

import com.carrito.saas.repository.enums.OrderType;
import com.carrito.saas.repository.enums.PaymentMethod;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * The operator's data for confirming an order proposal (T7a.2 of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p>These are EXACTLY the four pieces of data that {@code createOrder}
 * requires and that the proposal does not have (eje 1 of the T7a spec):
 * {@code customerName}, {@code orderType}, {@code paymentMethod} and
 * {@code customerAddress}. None of them exists anywhere in the inbound
 * WhatsApp path, so the operator must supply them at confirmation time.</p>
 *
 * <ul>
 *   <li>{@code customerName} is a CORRECTION of Meta's draft (the proposal
 *     carries {@code contacts[].profile.name}): a WhatsApp profile name is an
 *     apodo — a useful default, NEVER final data. Blank here falls back to the
 *     proposal's draft; if both are blank the confirmation is rejected.</li>
 *   <li>{@code customerAddress} is required only for {@code DELIVERY};
 *     {@code RETIRO} needs none.</li>
 *   <li>{@code customerPhone} is deliberately NOT here: it comes free from
 *     the proposal's {@code fromPhone} — the same datum, already captured,
 *     and it never travels through the operator's hands.</li>
 * </ul>
 */
@Data
public class ConfirmProposalRequestDTO {

	/** Operator-corrected customer name; blank falls back to the proposal's draft. */
	private String customerName;

	@NotNull
	private OrderType orderType;

	@NotNull
	private PaymentMethod paymentMethod;

	/** Required only when {@code orderType} is {@code DELIVERY}. */
	private String customerAddress;

	private String notes;

}
