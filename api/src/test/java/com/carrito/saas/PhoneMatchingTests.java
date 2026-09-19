package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.service.whatsapp.PhoneMatching;
import com.carrito.saas.service.whatsapp.PhoneResolution;

/**
 * Contract of {@link PhoneMatching} — the phone-matching rule as a PURE
 * function (T3c of {@code odd/tasks/whatsapp-inbound.md}): a collection of
 * {@code (businessId, phone)} candidates, a sender value, a default region,
 * and a {@link PhoneResolution}. No persistence, no Spring, no database.
 *
 * <p><strong>Why this class exists.</strong> T3c enforces "each location has
 * its own WhatsApp number" in the schema — {@code CHECK} on the canonical
 * shape plus {@code UNIQUE} on {@code businesses.phone}. From then on two
 * businesses CANNOT share a canonical number through valid data, so the
 * resolver's {@link PhoneResolution.Ambiguous} outcome is unreachable through
 * the database and untestable through it. This project's rule is that every
 * layer of defence needs a test that fails when that layer is removed, so the
 * ambiguity coverage moved HERE, against the pure function, when the
 * guarantee moved into the schema. The database-level tests that previously
 * seeded two businesses with the same subscriber
 * ({@code sameSubscriberInLocalAndInternationalFormatsIsAmbiguous},
 * {@code identicalStoredNumbersAreAmbiguous} in
 * {@code WhatsappPhoneResolverTests}) were re-homed verbatim in intent, not
 * dropped, and the constraint tests that replaced them prove the state is
 * now refused at INSERT.</p>
 *
 * <p>Candidates are plain {@link Business} entities built in memory
 * ({@code businesses.id} is manually assigned); they are never persisted.</p>
 */
class PhoneMatchingTests {

	private static final Long BIZ_LOCAL_FORMAT_ID = 987_900_101L;
	private static final Long BIZ_INTL_FORMAT_ID = 987_900_102L;
	private static final Long BIZ_SINGLE_ID = 987_900_103L;

	/** What Meta sends: bare international digits, no '+', no decoration. */
	private static final String META_BARE = "5491122334455";

	/** The international spelling an admin might have typed for the same number. */
	private static final String STORED_INTL = "+54 9 11 2233-4455";

	/**
	 * The realistic LOCAL spelling of the same mobile subscriber: trunk 0,
	 * area code, and the domestic mobile prefix 15.
	 */
	private static final String STORED_LOCAL_15 = "011 15 2233 4455";

	/**
	 * The landline spelling of the same subscriber digits: trunk 0 + area
	 * code, NO 15. Canonicalizes to a DIFFERENT E.164 number (see the
	 * empirical format table in {@code WhatsappPhoneResolverTests}).
	 */
	private static final String STORED_LANDLINE = "011 2233-4455";

	/** A duplicated country code: parseable digits, but NOT a valid AR number. */
	private static final String STORED_DUP_CODE = "+54 54 9 11 2233-4455";

	/**
	 * The canonical digits an admin stores since T3c: the E.164 form of the
	 * mobile subscriber, without the leading '+' — here spelled identically to
	 * the bare Meta sender.
	 */
	private static final String STORED_CANONICAL = "5491122334455";

	private Business candidate(Long id, String phone) {
		Business business = new Business();
		business.setId(id);
		business.setName("PhoneMatching " + id);
		business.setSlug("phone-matching-" + id);
		business.setPhone(phone);
		return business;
	}

	/**
	 * THE ambiguous case, re-homed from the database tests: two candidates,
	 * the same subscriber, one phone in a local format and one in the
	 * international format. Both canonicalize to the same E.164 number, so
	 * the result is AMBIGUOUS — and never a choice of "the first one".
	 * Under the T3c constraints this state cannot be inserted, which is
	 * exactly why the test lives against the pure function.
	 */
	@Test
	void sameSubscriberInLocalAndInternationalFormatsIsAmbiguous() {
		List<Business> candidates = List.of(
				candidate(BIZ_LOCAL_FORMAT_ID, STORED_LOCAL_15),
				candidate(BIZ_INTL_FORMAT_ID, STORED_INTL));

		PhoneResolution result = PhoneMatching.match(candidates, META_BARE, "AR");

		assertThat(result).isNotInstanceOf(PhoneResolution.Resolved.class);
		assertThat(result).isInstanceOfSatisfying(PhoneResolution.Ambiguous.class,
				ambiguous -> assertThat(ambiguous.businessIds())
						.containsExactlyInAnyOrder(BIZ_LOCAL_FORMAT_ID, BIZ_INTL_FORMAT_ID));
	}

	/**
	 * Re-homed: the same subscriber stored twice in the SAME format is
	 * ambiguous too — the guard comes from the canonical form, not from
	 * formatting differences.
	 */
	@Test
	void identicalStoredNumbersAreAmbiguous() {
		List<Business> candidates = List.of(
				candidate(BIZ_LOCAL_FORMAT_ID, STORED_INTL),
				candidate(BIZ_INTL_FORMAT_ID, STORED_INTL));

		assertThat(PhoneMatching.match(candidates, META_BARE, "AR"))
				.isInstanceOf(PhoneResolution.Ambiguous.class);
	}

	/**
	 * A number owned by exactly one candidate resolves to it — the same
	 * outcome the resolver delegates for, exercised without the database.
	 */
	@Test
	void singleMatchingCandidateIsResolved() {
		List<Business> candidates = List.of(
				candidate(BIZ_SINGLE_ID, STORED_LOCAL_15),
				candidate(987_900_200L, null));

		assertThat(PhoneMatching.match(candidates, META_BARE, "AR"))
				.isInstanceOfSatisfying(PhoneResolution.Resolved.class,
						resolved -> assertThat(resolved.business().getId()).isEqualTo(BIZ_SINGLE_ID));
	}

	/**
	 * Re-homed: the landline spelling of the subscriber digits does NOT
	 * bridge the mobile Meta sender — the matching level shows the same
	 * non-bridging limitation the resolver-level test used to pin (the
	 * canonicalization-level assertion lives in the empirical format table).
	 */
	@Test
	void landlineSpellingDoesNotBridgeTheMobileSender() {
		List<Business> candidates = List.of(candidate(BIZ_SINGLE_ID, STORED_LANDLINE));

		assertThat(PhoneMatching.match(candidates, META_BARE, "AR"))
				.isInstanceOf(PhoneResolution.NotFound.class);
	}

	/**
	 * Re-homed: blank, whitespace and invalid stored phones match nothing —
	 * not a real number, not each other. Under the T3c constraints such
	 * values cannot be stored any more; the pure function keeps the defence
	 * for any row that predates the constraint.
	 */
	@Test
	void blankWhitespaceAndInvalidStoredPhonesMatchNothing() {
		List<Business> candidates = List.of(
				candidate(987_900_301L, ""),
				candidate(987_900_302L, "   "),
				candidate(987_900_303L, STORED_DUP_CODE));

		assertThat(PhoneMatching.match(candidates, META_BARE, "AR"))
				.isInstanceOf(PhoneResolution.NotFound.class);
	}

	/**
	 * Re-homed: a digit-plausible but invalid stored value (duplicated
	 * country code) is refused, not repaired — it canonicalizes to nothing
	 * and matches nothing, on both the lookup and the stored side.
	 */
	@Test
	void duplicatedCountryCodeIsNotSecondGuessed() {
		List<Business> candidates = List.of(candidate(BIZ_SINGLE_ID, STORED_DUP_CODE));

		assertThat(PhoneMatching.match(candidates, META_BARE, "AR"))
				.isInstanceOf(PhoneResolution.NotFound.class);
		assertThat(PhoneMatching.match(candidates, STORED_DUP_CODE, "AR"))
				.isInstanceOf(PhoneResolution.NotFound.class);
		assertThat(PhoneMatching.match(candidates, "545491122334455", "AR"))
				.isInstanceOf(PhoneResolution.NotFound.class);
	}

	/**
	 * A default region configured lowercase or whitespace-padded still
	 * resolves the bare-digit Meta sender. {@code WhatsappProperties.validate()}
	 * accepts such spellings (case- and whitespace-tolerant), so the parse
	 * side must normalize the region the SAME way — the normalization lives in
	 * one place shared by validation and parsing. The bare-digit form is the
	 * exact shape Meta sends; parsed against a raw lowercase region it
	 * canonicalizes to {@code null} and every message resolves to NotFound.
	 */
	@Test
	void lowercaseAndWhitespacePaddedDefaultRegionStillResolves() {
		List<Business> candidates = List.of(candidate(BIZ_SINGLE_ID, STORED_CANONICAL));

		for (String region : new String[] { "ar", " AR ", "Ar" }) {
			assertThat(PhoneMatching.match(candidates, META_BARE, region))
					.as("bare Meta sender resolves with default region: '%s'", region)
					.isInstanceOfSatisfying(PhoneResolution.Resolved.class,
							resolved -> assertThat(resolved.business().getId()).isEqualTo(BIZ_SINGLE_ID));
		}
	}

	/** No candidates at all: not-found, never an exception. */
	@Test
	void emptyCandidateListIsNotFound() {
		assertThat(PhoneMatching.match(List.of(), META_BARE, "AR"))
				.isInstanceOf(PhoneResolution.NotFound.class);
	}

	/**
	 * A sender value with no canonical form — null, blank, garbage — matches
	 * nothing by rule and raises nothing.
	 */
	@Test
	void senderValueWithoutCanonicalFormIsNotFound() {
		List<Business> candidates = List.of(candidate(BIZ_SINGLE_ID, STORED_INTL));

		assertThat(PhoneMatching.match(candidates, "", "AR"))
				.isInstanceOf(PhoneResolution.NotFound.class);
		assertThat(PhoneMatching.match(candidates, "   ", "AR"))
				.isInstanceOf(PhoneResolution.NotFound.class);
		assertThat(PhoneMatching.match(candidates, null, "AR"))
				.isInstanceOf(PhoneResolution.NotFound.class);
		assertThat(PhoneMatching.match(candidates, "not a phone at all", "AR"))
				.isInstanceOf(PhoneResolution.NotFound.class);
	}
}
