package com.carrito.saas.repository.enums;

/**
 * Per-line interpretation state of an {@link OrderProposalItem} — how the
 * normalizer treated the phrase behind that line (T6b of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p>This is the operator-facing distinction that the proposal-level
 * {@link ProposalStatus} deliberately does NOT carry: {@code PENDING} means
 * "waiting for a human decision" and cannot say WHY. The line states say why:
 * what is safe, what needs the operator's explicit acceptance, and what the
 * customer has to re-write. The T6a obligations for T7/T8 ride on these
 * values — a {@code SUGGESTED} line must be accepted explicitly before it may
 * become an order line (never placed silently, never blocking-free), and a
 * proposal carrying {@code SUGGESTED}/{@code AMBIGUOUS} lines must not be
 * presented as confirmable-complete.</p>
 */
public enum ItemResolution {

	/**
	 * The phrase matched a catalog entry's FULL name exactly. Carries the
	 * resolved product or combo id, the catalog name, the quantity and the raw
	 * phrase. This is the only state produced by an exact match.
	 */
	RESOLVED,

	/**
	 * A PREFIX match against exactly ONE catalog entry: a proposal the
	 * operator must accept EXPLICITLY. Carries the candidate's product or
	 * combo id and name (so acceptance can place the line without re-matching)
	 * plus quantity and raw phrase. A prefix match NEVER has this table's
	 * {@code RESOLVED} state — the normalizer's structural guarantee, now
	 * visible in the data.
	 */
	SUGGESTED,

	/**
	 * The phrase matched two or more catalog entries with equal specificity.
	 * Carries NO ids (the matcher never chooses) and NO single name; the
	 * candidate names are kept in {@code OrderProposalItem.candidates} so the
	 * operator can ask the customer for precision without re-running anything.
	 */
	AMBIGUOUS,

	/**
	 * The phrase matched nothing; the raw text is kept verbatim in
	 * {@code OrderProposalItem.rawLine}. Losing what was not understood would
	 * defeat the purpose of recording the message.
	 */
	UNRESOLVED
}
