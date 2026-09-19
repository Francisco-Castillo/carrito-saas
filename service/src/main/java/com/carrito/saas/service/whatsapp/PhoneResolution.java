package com.carrito.saas.service.whatsapp;

import com.carrito.saas.repository.entity.Business;

import java.util.List;

/**
 * The outcome of trying to route an inbound message to a business by sender
 * phone. Resolution has exactly three outcomes, and the type makes the
 * dangerous one impossible to misuse:
 *
 * <ul>
 *   <li>{@link Resolved} — exactly one business owns the number;</li>
 *   <li>{@link NotFound} — no business owns it (including a null, blank or
 *       digit-less lookup value: those match nothing by rule);</li>
 *   <li>{@link Ambiguous} — two or more businesses share the number. This is
 *       a first-class outcome, not an error: the number is <strong>never</strong>
 *       resolved to "the first one".</li>
 * </ul>
 *
 * <p>The ambiguity guard is structural, not conventional: {@link Ambiguous}
 * carries only the ids of the conflicting businesses and has no component
 * that yields a single {@link Business}, so a caller physically cannot
 * obtain a chosen business from an ambiguous result — it must handle the
 * case explicitly (e.g. reject and record, which is a later task's job).</p>
 */
public sealed interface PhoneResolution
		permits PhoneResolution.Resolved, PhoneResolution.NotFound, PhoneResolution.Ambiguous {

	/** Exactly one business owns the number. */
	record Resolved(Business business) implements PhoneResolution {
	}

	/** No business owns the number; nothing was chosen. */
	record NotFound() implements PhoneResolution {
	}

	/**
	 * Several businesses share the number; nothing was chosen.
	 *
	 * @param businessIds ids of every business whose canonical phone equals
	 *                    the looked-up value; never a single business
	 */
	record Ambiguous(List<Long> businessIds) implements PhoneResolution {
	}
}
