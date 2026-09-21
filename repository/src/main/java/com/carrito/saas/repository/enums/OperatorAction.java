package com.carrito.saas.repository.enums;

/**
 * The explicit action an operator took on ONE line of an
 * {@link com.carrito.saas.repository.entity.OrderProposal} (T7b.1 of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p><strong>The action's legality depends on the line's
 * {@link ItemResolution}</strong> — an {@code ACCEPTED} on an
 * {@code UNRESOLVED} line is not a state this system may represent. The
 * legality matrix lives in EXACTLY ONE place, the service:
 * {@code OrderProposalServiceImpl#isLegalAction(ItemResolution, OperatorAction)}.
 * This enum carries the values and their meaning, never the pairing rule;
 * keeping the rule here would give it a second home that can drift from the
 * one the service enforces.</p>
 *
 * <ul>
 *   <li>{@link #ACCEPTED} — the operator explicitly accepts a
 *     {@code SUGGESTED} line: the prefix candidate becomes placeable.</li>
 *   <li>{@link #CHOSEN} — the operator picked one candidate NAME of an
 *     {@code AMBIGUOUS} line (recorded in
 *     {@code OrderProposalItem.chosenName}); the service re-resolves that
 *     name against the live catalog and fills {@code product_id}/{@code combo_id}.</li>
 *   <li>{@link #DISCARDED} — the operator drops the line: it never reaches
 *     the order, but it stays on the proposal (nothing is silently lost).</li>
 * </ul>
 */
public enum OperatorAction {

	ACCEPTED,

	CHOSEN,

	DISCARDED
}
