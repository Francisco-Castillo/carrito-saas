package com.carrito.saas.service.whatsapp;

/**
 * Routes an inbound message to its business by sender phone.
 *
 * <p>Implementations decide which business a message belongs to, or refuse
 * to decide: the result is a {@link PhoneResolution} — resolved, not found,
 * or ambiguous — and never a guess. Recording the refusal (a FAILED
 * proposal) is the caller's job, not the resolver's: this port does not
 * persist anything.</p>
 */
public interface IBusinessPhoneResolver {

	/**
	 * Resolves the sender phone of an inbound message to a business.
	 *
	 * @param fromPhone the raw sender number as the provider sent it (Meta
	 *                  sends bare digits); the canonical form is derived here
	 * @return exactly one of: resolved to a single business, not found, or
	 *         ambiguous because several businesses share the number
	 */
	PhoneResolution resolve(String fromPhone);
}
