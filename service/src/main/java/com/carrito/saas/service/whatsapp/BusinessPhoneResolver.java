package com.carrito.saas.service.whatsapp;

import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.service.config.WhatsappProperties;

/**
 * Resolves an inbound message's sender phone to its business, or refuses to.
 *
 * <p><strong>The adapter of the T3c split.</strong> Since the schema enforces
 * "each location has its own number" — {@code businesses.phone} is CHECKed to
 * the canonical E.164 digits form and UNIQUE, applied by hand exactly as for
 * {@code businesses.slug} (see the {@code Business.phone} javadoc for the
 * exact SQL) — the matching logic itself lives in {@link PhoneMatching}, a
 * pure function with no persistence and no Spring. This class only loads the
 * candidates and delegates: the ambiguous case is now unreachable through
 * valid data, so it is tested against the pure function
 * ({@code PhoneMatchingTests}), not through the database.</p>
 *
 * <p>The lookup loads the businesses whose phone is non-null once per
 * message; at this scale (locales of one city) that is correct and simple.
 * Both sides of the comparison still go through the single rule of
 * {@link PhoneNumbers}: the sender value as Meta sent it and every stored
 * phone — idempotent for already-canonical stored values (the constrained
 * normal case), and robust for any row that predates the constraint, which
 * canonicalizes to nothing and matches nothing rather than throwing.</p>
 *
 * <p>Ambiguity remains a first-class outcome of {@link PhoneMatching}: if
 * two or more businesses ever share the canonical number, the result is
 * {@link PhoneResolution.Ambiguous} and <strong>no business is chosen</strong>
 * — {@code PhoneResolution.Ambiguous} exposes only the conflicting ids, so a
 * caller physically cannot route to one of them. Recording that refusal is
 * the caller's job; this class persists nothing.</p>
 */
@Component
public class BusinessPhoneResolver implements IBusinessPhoneResolver {

	private final BusinessRepository businessRepository;

	private final WhatsappProperties whatsappProperties;

	public BusinessPhoneResolver(BusinessRepository businessRepository,
			WhatsappProperties whatsappProperties) {
		this.businessRepository = businessRepository;
		this.whatsappProperties = whatsappProperties;
	}

	@Override
	public PhoneResolution resolve(String fromPhone) {
		List<Business> candidates = businessRepository.findAllByPhoneIsNotNull();
		return PhoneMatching.match(candidates, fromPhone,
				whatsappProperties.getDefaultRegion());
	}
}
