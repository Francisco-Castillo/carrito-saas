package com.carrito.saas.repository.enums;

/**
 * Lifecycle of an inbound message proposal (T5 of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p>This is the proposal's OWN state machine, deliberately NOT an extension
 * of {@link OrderStatus}: a proposal is a candidate order that a human must
 * confirm before anything reaches the kitchen or the stock. Reusing
 * {@code OrderStatus} would leak proposal states into the order queries and
 * the KDS broadcast (Hecho 2 of the feature document).</p>
 *
 * <p>The transition to {@code CONFIRMED} (T7) happens by building an
 * {@code OrderRequestDTO} and calling the single order writer; the proposal
 * row itself is only ever marked, it never becomes an {@code Order} row.</p>
 */
public enum ProposalStatus {

	/** Interpreted proposal waiting for a human decision. */
	PENDING,

	/** A human confirmed the proposal; the resulting Order was created via createOrder. */
	CONFIRMED,

	/** A human discarded the proposal; nothing else changed (no stock, no order). */
	REJECTED,

	/** The message could not be interpreted or routed; the reason is on the proposal. */
	FAILED
}
