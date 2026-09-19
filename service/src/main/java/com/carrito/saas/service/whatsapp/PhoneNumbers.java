package com.carrito.saas.service.whatsapp;

import java.util.Locale;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

/**
 * The single definition of the phone canonicalization rule used to compare an
 * inbound WhatsApp sender number against the {@code businesses.phone} column.
 *
 * <p><strong>Canonical form = E.164</strong> (e.g. {@code +5491122334455}),
 * computed with Google's libphonenumber — the reference implementation of the
 * standard. Meta sends the sender number as bare digits
 * ({@code 5491122334455}) while the stored value is whatever the admin typed
 * ({@code +54 9 11 2233-4455}, {@code 011 2233-4455}, {@code 11 2233-4455}),
 * so BOTH sides go through this one method before comparing. A value that
 * carries no country code is interpreted against the configurable default
 * region ({@code whatsapp.default-region}, default {@code AR}): that is the
 * whole point — a local spelling and the international spelling of the same
 * subscriber must meet in the same E.164 form.</p>
 *
 * <p><strong>Deliberate strictness: {@code isValidNumber}, not
 * {@code isPossibleNumber}.</strong> A too-loose rule produces a FALSE MATCH,
 * and a false match routes a customer's order to the WRONG business — worse
 * than a missed match, which surfaces as a {@code NotFound} the caller
 * records. {@code isPossibleNumber} only checks length and would accept
 * numbers with impossible area codes or the wrong country code for their
 * length; {@code isValidNumber} additionally validates the national number
 * pattern for its region. The cost is the opposite failure mode: a stored
 * number whose pattern the current metadata does not recognize canonicalizes
 * to {@code null} and matches nothing — a visible {@code NotFound} in the
 * proposal record, correctable by the admin, rather than a silent wrong
 * routing.</p>
 *
 * <p>Unparseable, blank or invalid values have NO canonical form: they return
 * {@code null} and therefore match nothing. They never throw —
 * {@code businesses.phone} is unconstrained and businesses are created by
 * hand-written SQL today, so dirty data is expected, not exceptional.</p>
 *
 * <p><strong>One runtime, on purpose.</strong> The previous design normalized
 * the inbound side in Java and the stored side in SQL ("one rule, two
 * runtimes"); E.164 reasoning cannot be expressed in {@code regexp_replace},
 * so the SQL implementation, its functional index and the Java&#8594;SQL
 * agreement test are gone. There is exactly one implementation of the rule —
 * this one — and the drift risk it guarded against has been removed instead
 * of tested. The comparison happens in memory over the businesses whose
 * phone is non-null: at the scale of one city that is correct and simple.</p>
 */
public final class PhoneNumbers {

	private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

	private PhoneNumbers() {
	}

	/**
	 * The SINGLE normalization point for the configured default region: both
	 * the region validation ({@link #isSupportedRegion}, used by
	 * {@code WhatsappProperties.validate()}) and the parse
	 * ({@link #canonicalizeToE164}, used by the resolver) see the SAME
	 * normalized value, so a spelling that validation accepts can never fail
	 * at parse time. Keep every region comparison routed through this method —
	 * a second normalization site is exactly the divergence this removes.
	 *
	 * @param region the configured region code, in any spelling; may be null
	 * @return the region trimmed and uppercased (e.g. {@code " ar "} becomes
	 *         {@code "AR"}); {@code null} stays {@code null}
	 */
	private static String normalizeRegion(String region) {
		return region == null ? null : region.trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * Reduces a raw phone value to its canonical E.164 form.
	 *
	 * @param raw the raw value (as Meta sent it, or as the admin typed it);
	 *            may carry a leading {@code +} and full country code, or be a
	 *            local spelling without one
	 * @param defaultRegion ISO 3166-1 alpha-2 region used to interpret values
	 *                      that carry no country code (e.g. {@code AR}); must
	 *                      be a supported region; case- and whitespace-tolerant,
	 *                      normalized exactly as {@link #isSupportedRegion}
	 *                      normalizes it
	 * @return the E.164 form (leading {@code +}) when the value parses to a
	 *         number VALID for its region; {@code null} when the value is
	 *         null, blank, unparseable, or not a valid number — it matches
	 *         nothing and raises nothing
	 */
	public static String canonicalizeToE164(String raw, String defaultRegion) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			Phonenumber.PhoneNumber number = UTIL.parse(raw, normalizeRegion(defaultRegion));
			if (!UTIL.isValidNumber(number)) {
				return null;
			}
			return UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
		}
		catch (NumberParseException | RuntimeException e) {
			// Dirty data must match nothing, never blow up the resolver.
			return null;
		}
	}

	/**
	 * Whether {@code region} names a region libphonenumber can interpret
	 * local spellings against. Used by {@code WhatsappProperties.validate()}
	 * to refuse to start the application on a region code that would silently
	 * fail to canonicalize every locally stored number.
	 *
	 * @param region the configured region code; case- and whitespace-tolerant
	 * @return true only for a non-blank, supported ISO 3166-1 alpha-2 code
	 */
	public static boolean isSupportedRegion(String region) {
		if (region == null || region.isBlank()) {
			return false;
		}
		return UTIL.getSupportedRegions().contains(normalizeRegion(region));
	}
}
