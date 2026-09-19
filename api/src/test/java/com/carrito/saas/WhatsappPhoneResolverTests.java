package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.service.whatsapp.IBusinessPhoneResolver;
import com.carrito.saas.service.whatsapp.PhoneNumbers;
import com.carrito.saas.service.whatsapp.PhoneResolution;

/**
 * Contract of the phone-to-business resolver (T3, comparison rule superseded
 * by T3b, storage policy enforced by T3c of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p>The resolver decides which business an inbound WhatsApp message belongs
 * to — or refuses to decide. Since T3c, "each location has its own WhatsApp
 * number" is enforced by the SCHEMA: {@code businesses.phone} stores
 * canonical E.164 digits without the {@code +} (or NULL), guarded by
 * {@code CHECK (phone IS NULL OR phone ~ '^[1-9][0-9]{7,14}$')} and
 * {@code UNIQUE (phone)}, applied by hand exactly as for
 * {@code businesses.slug} (see the {@code Business.phone} javadoc for the
 * exact SQL). A plain {@code UNIQUE} on a free-text column would not enforce
 * the policy: {@code 011 2233-4455} and {@code +54 9 11 2233-4455} are
 * different strings for the SAME subscriber, and the equality the resolver
 * uses — E.164, computed by libphonenumber — is not expressible as a SQL
 * constraint. The constraint enforces the policy only because <em>what is
 * stored is what is compared</em>.</p>
 *
 * <p><strong>Consequence for this suite (T3c)</strong>: a phone in any
 * non-canonical spelling can no longer be INSERTED, so the tests that seeded
 * two businesses with the same canonical subscriber
 * ({@code sameSubscriberInLocalAndInternationalFormatsIsAmbiguous},
 * {@code identicalStoredNumbersAreAmbiguous}) and the dirty-value seeds
 * ({@code landlineSpellingDoesNotBridgeTheMobileSender},
 * {@code nullBlankUnparseableAndInvalidStoredPhonesMatchNothing} beyond the
 * null case, {@code duplicatedCountryCodeIsNotSecondGuessed}) moved to
 * {@link PhoneMatchingTests} — the pure matching function — where the
 * ambiguous and dirty-input defences stay testable without the database
 * producing the forbidden state. The tests that replaced them here prove the
 * database REFUSES those states. Nothing was weakened.</p>
 *
 * <p><strong>T3b — comparison rule (unchanged).</strong> Both sides are
 * canonicalized to E.164 with libphonenumber against the default region
 * ({@code whatsapp.default-region}, {@code AR}); canonicalization of the
 * stored side is idempotent for the now-mandated canonical form and kept as
 * defence for pre-constraint rows.</p>
 *
 * <p><strong>Empirical format table (observed with libphonenumber 9.0.39,
 * region AR, not assumed)</strong> — pinned by
 * {@link #formatTableCanonicalizesExactlyAsObservedWithRegionAR()}:</p>
 *
 * <table summary="observed canonical forms">
 * <tr><th>stored input</th><th>E.164</th><th>meets Meta sender 5491122334455?</th></tr>
 * <tr><td>{@code 011 15 2233 4455}</td><td>{@code +5491122334455}</td><td>YES — the 15 mobile prefix is stripped and the international 9 restored</td></tr>
 * <tr><td>{@code +54 9 11 2233-4455}</td><td>{@code +5491122334455}</td><td>YES</td></tr>
 * <tr><td>{@code 5491122334455}</td><td>{@code +5491122334455}</td><td>YES</td></tr>
 * <tr><td>{@code 011 2233-4455}</td><td>{@code +541122334455}</td><td>NO — landline form, a DIFFERENT E.164 number</td></tr>
 * <tr><td>{@code 11 2233-4455}</td><td>{@code +541122334455}</td><td>NO</td></tr>
 * <tr><td>{@code 01122334455}</td><td>{@code +541122334455}</td><td>NO</td></tr>
 * </table>
 *
 * <p><strong>Known coverage limitation (reported, not hidden)</strong>: the
 * trunk-0 landline spelling of the subscriber digits does NOT bridge the
 * mobile Meta sender — libphonenumber treats {@code 11 2233-4455} as a
 * FIXED_LINE number whose E.164 form differs from the mobile's. In the
 * Argentine plan these are genuinely different numbers (the fixed-line
 * spelling belongs to a fixed line), so refusing to bridge is the
 * conservative outcome: a forced bridge would risk routing an order to the
 * wrong business. Under the T3c CHECK the landline spelling cannot even be
 * stored; an admin who wants the mobile subscriber stores its canonical
 * digits. The matching-level non-bridging behaviour is pinned in
 * {@code PhoneMatchingTests#landlineSpellingDoesNotBridgeTheMobileSender}.</p>
 *
 * <p>{@code businesses.id} is manually assigned (no generator), so fixtures
 * use high fixed ids. {@code @Transactional} rolls every fixture back.</p>
 */
@SpringBootTest
@Transactional
class WhatsappPhoneResolverTests {

	private static final Long BIZ_PRETTY_ID = 987_800_101L;
	private static final Long BIZ_BARE_ID = 987_800_102L;
	private static final Long BIZ_NULL_PHONE_ID = 987_800_105L;

	/** What Meta sends: bare international digits, no '+', no decoration. */
	private static final String META_BARE = "5491122334455";

	/** The international spelling an admin might have typed for the same number. */
	private static final String STORED_INTL = "+54 9 11 2233-4455";

	/**
	 * The realistic LOCAL spelling of the same mobile subscriber: trunk 0,
	 * area code, and the domestic mobile prefix 15. Under the T3c CHECK this
	 * value can no longer be STORED (it fails the shape constraint) — it is
	 * kept as a lookup-free constant for the constraint rejection tests and
	 * for the pure-function suite.
	 */
	private static final String STORED_LOCAL_15 = "011 15 2233 4455";

	/**
	 * The canonical digits an admin stores since T3c: the E.164 form of the
	 * mobile subscriber, without the leading '+'.
	 */
	private static final String STORED_CANONICAL = "5491122334455";

	@Autowired
	private IBusinessPhoneResolver resolver;

	@Autowired
	private BusinessRepository businessRepository;

	private Business seedBusiness(Long id, String slug, String phone) {
		Business business = new Business();
		business.setId(id);
		business.setName("Phone Resolver " + slug);
		business.setSlug(slug);
		business.setPhone(phone);
		return businessRepository.saveAndFlush(business);
	}

	/**
	 * The REAL database path with a single business: a canonical stored
	 * number resolves the bare Meta sender form to that business.
	 */
	@Test
	void singleBusinessResolvesToExactlyThatBusiness() {
		seedBusiness(BIZ_PRETTY_ID, "phone-resolver-pretty", STORED_CANONICAL);

		PhoneResolution result = resolver.resolve(META_BARE);

		assertThat(result).isInstanceOfSatisfying(PhoneResolution.Resolved.class,
				resolved -> assertThat(resolved.business().getId()).isEqualTo(BIZ_PRETTY_ID));
	}

	/**
	 * The reverse comparison: stored bare canonical digits, looked up with a
	 * pretty international spelling. The LOOKUP side is free text (Meta
	 * could send it decorated), so both sides canonicalize — the stored side
	 * is idempotent on the canonical form.
	 */
	@Test
	void storedBareResolvesPrettyLookup() {
		seedBusiness(BIZ_BARE_ID, "phone-resolver-stored-bare", META_BARE);

		assertThat(resolver.resolve(STORED_INTL))
				.isInstanceOfSatisfying(PhoneResolution.Resolved.class,
						resolved -> assertThat(resolved.business().getId()).isEqualTo(BIZ_BARE_ID));
	}

	/**
	 * The empirical format table, established by observation (see the class
	 * javadoc) and pinned here: which realistic spellings canonicalize to the
	 * Meta sender form and which do not. The landline forms deliberately do
	 * NOT meet the sender — asserting otherwise would invent an expectation.
	 */
	@Test
	void formatTableCanonicalizesExactlyAsObservedWithRegionAR() {
		// (raw, expected E.164 or null, label)
		record Entry(String raw, String expectedE164, String label) {
		}

		for (Entry entry : new Entry[] {
				new Entry(STORED_LOCAL_15, "+5491122334455",
						"local 15 spelling bridges the Meta sender"),
				new Entry(STORED_INTL, "+5491122334455",
						"international pretty spelling bridges the Meta sender"),
				new Entry(META_BARE, "+5491122334455",
						"bare Meta sender form is already E.164"),
				new Entry("011 2233-4455", "+541122334455",
						"landline spelling canonicalizes to a DIFFERENT number: does not bridge (limitation)"),
				new Entry("11 2233-4455", "+541122334455",
						"landline spelling without trunk: same, does not bridge (limitation)"),
				new Entry("01122334455", "+541122334455",
						"landline digits without decoration: same, does not bridge (limitation)") }) {
			assertThat(PhoneNumbers.canonicalizeToE164(entry.raw(), "AR"))
					.as("canonical form of: %s (%s)", entry.raw(), entry.label())
					.isEqualTo(entry.expectedE164());
		}
	}

	/** A number no business owns resolves to not-found; nothing is chosen. */
	@Test
	void unknownNumberIsNotFound() {
		seedBusiness(BIZ_PRETTY_ID, "phone-resolver-known", STORED_CANONICAL);

		PhoneResolution result = resolver.resolve("5499999999999");

		assertThat(result).isInstanceOf(PhoneResolution.NotFound.class);
	}

	/**
	 * A NULL stored phone is legal (Postgres treats NULLs as distinct in the
	 * unique index) and matches nothing.
	 */
	@Test
	void nullStoredPhoneIsLegalAndMatchesNothing() {
		seedBusiness(BIZ_NULL_PHONE_ID, "phone-resolver-null", null);

		assertThat(resolver.resolve(META_BARE)).isInstanceOf(PhoneResolution.NotFound.class);
		assertThat(resolver.resolve("")).isInstanceOf(PhoneResolution.NotFound.class);
		assertThat(resolver.resolve(null)).isInstanceOf(PhoneResolution.NotFound.class);
	}

	/**
	 * T3c RED/GREEN: the database itself refuses a SECOND business whose
	 * phone is the canonical number of one already owned. The policy "each
	 * location has its own WhatsApp number" is enforced where the data lives,
	 * so the resolver's ambiguous path is a defence for an unreachable state
	 * — tested against the pure function in {@code PhoneMatchingTests}, not
	 * here.
	 */
	@Test
	void duplicateCanonicalPhoneIsRejectedByTheDatabase() {
		seedBusiness(987_800_201L, "phone-constraint-dup-a", STORED_CANONICAL);

		assertThatExceptionOfType(DataIntegrityViolationException.class)
				.isThrownBy(() -> seedBusiness(987_800_202L, "phone-constraint-dup-b", STORED_CANONICAL));
	}

	/**
	 * T3c RED/GREEN: the database refuses a NON-canonical stored value. The
	 * realistic local spelling of the same subscriber (trunk 0, the 15 mobile
	 * prefix, spaces) fails the shape CHECK — what is stored must be what the
	 * resolver compares (canonical E.164 digits), otherwise {@code UNIQUE}
	 * would silently tolerate two spellings of one subscriber.
	 */
	@Test
	void nonCanonicalPhoneIsRejectedByTheDatabase() {
		assertThatExceptionOfType(DataIntegrityViolationException.class)
				.isThrownBy(() -> seedBusiness(BIZ_PRETTY_ID, "phone-constraint-shape", STORED_LOCAL_15));
	}

	/**
	 * Triangulation of the CHECK's NULL exception: a business WITHOUT a
	 * WhatsApp number is legal, but a BLANK value is not — it fails the
	 * shape CHECK like any other non-canonical value. One violation per
	 * test: Postgres aborts the whole transaction at the first CHECK
	 * violation, so two cannot coexist inside one {@code @Transactional}
	 * test.
	 */
	@Test
	void blankPhoneIsRejectedByTheDatabase() {
		assertThatExceptionOfType(DataIntegrityViolationException.class)
				.isThrownBy(() -> seedBusiness(987_800_301L, "phone-constraint-blank", ""));
	}

	/** The whitespace-only variant of {@link #blankPhoneIsRejectedByTheDatabase}. */
	@Test
	void whitespacePhoneIsRejectedByTheDatabase() {
		assertThatExceptionOfType(DataIntegrityViolationException.class)
				.isThrownBy(() -> seedBusiness(987_800_302L, "phone-constraint-whitespace", "   "));
	}
}
