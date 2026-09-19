package com.carrito.saas.service.whatsapp;

import java.util.List;

import com.carrito.saas.repository.entity.Business;

/**
 * The phone-matching rule as a PURE function: no persistence, no Spring, no
 * I/O — a collection of candidate businesses, an inbound sender value, the
 * configured default region, and a {@link PhoneResolution}.
 *
 * <p><strong>Why this exists (T3c).</strong> The {@code CHECK} +
 * {@code UNIQUE} constraints on {@code businesses.phone} make it impossible
 * for two businesses to share a canonical number through valid data, so the
 * resolver's {@link PhoneResolution.Ambiguous} outcome is unreachable
 * through the database — and therefore UNTESTABLE through the database. This
 * project's rule is that every layer of defence needs a test that fails when
 * that layer is removed; moving the guarantee into the schema must not mean
 * losing the defence's test. The matching logic lives here so the ambiguous
 * case is tested against this function directly, with no database involved
 * (see {@code PhoneMatchingTests}). The resolver is the adapter that loads
 * the candidates and delegates; the constraints are the layer below it.</p>
 *
 * <p><strong>One rule, both sides.</strong> The sender value and every
 * candidate's stored phone go through the single canonicalization of
 * {@link PhoneNumbers#canonicalizeToE164} with the configured default region
 * — Meta sends bare international digits ({@code 5491122334455}) while the
 * stored side is canonical E.164 digits without the {@code +}
 * ({@code 5491122334455}), and both reduce to the same {@code +}-prefixed
 * E.164 form here. Canonicalization is idempotent, so already-canonical
 * stored values are unchanged by it; it is kept on the stored side as
 * defence for any row that predates the constraint, not because dirty rows
 * are expected any more.</p>
 *
 * <p><strong>The dangerous outcome stays structurally guarded.</strong> If
 * two or more candidates share the canonical number — including the
 * realistic case of one stored in a local format and another in the
 * international format of the SAME subscriber, which is exactly the state
 * the constraints now forbid — the result is {@link PhoneResolution.Ambiguous}
 * and <strong>no business is chosen</strong>: not "the first one", not any.
 * {@link PhoneResolution.Ambiguous} exposes only the conflicting ids, so a
 * caller physically cannot route to one of them.</p>
 */
public final class PhoneMatching {

	private PhoneMatching() {
	}

	/**
	 * Matches the sender value against the candidate businesses.
	 *
	 * @param candidates    the businesses to match against (each contributing
	 *                      its {@code (id, phone)} pair); never {@code null},
	 *                      possibly empty
	 * @param fromPhone     the sender value as the provider sent it; may be
	 *                      blank, unparseable or invalid
	 * @param defaultRegion ISO 3166-1 alpha-2 region used to interpret values
	 *                      that carry no country code (e.g. {@code AR})
	 * @return {@link PhoneResolution.Resolved} when exactly one candidate
	 *         matches; {@link PhoneResolution.Ambiguous} with every matching
	 *         id when two or more do; {@link PhoneResolution.NotFound}
	 *         otherwise — including a sender value with no canonical form,
	 *         and candidates whose stored phone canonicalizes to nothing.
	 *         Never throws, never chooses among equals.
	 */
	public static PhoneResolution match(List<Business> candidates, String fromPhone, String defaultRegion) {
		String senderPhone = PhoneNumbers.canonicalizeToE164(fromPhone, defaultRegion);
		if (senderPhone == null) {
			return new PhoneResolution.NotFound();
		}

		List<Business> matches = candidates.stream()
				.filter(business -> senderPhone.equals(PhoneNumbers.canonicalizeToE164(
						business.getPhone(), defaultRegion)))
				.toList();

		if (matches.isEmpty()) {
			return new PhoneResolution.NotFound();
		}
		if (matches.size() == 1) {
			return new PhoneResolution.Resolved(matches.get(0));
		}
		return new PhoneResolution.Ambiguous(matches.stream().map(Business::getId).toList());
	}
}
