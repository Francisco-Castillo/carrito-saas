package com.carrito.saas.service.config;

import com.carrito.saas.service.whatsapp.PhoneNumbers;
import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the inbound WhatsApp Cloud API webhook.
 *
 * <p>Same registration mechanism as {@link QrProperties}: a {@code @Component}
 * plus {@code @ConfigurationProperties} bean bound from {@code application.yml},
 * where each value carries a {@code ${ENV_VAR:default}} placeholder so it can
 * be changed per environment without recompiling.</p>
 *
 * <p>Both values are secrets for a real deployment:</p>
 * <ul>
 *   <li>{@code appSecret} — the HMAC-SHA256 key Meta signs every webhook body
 *       with; the controller verifies it over the raw request bytes before
 *       parsing.</li>
 *   <li>{@code verifyToken} — the token Meta must present as
 *       {@code hub.verify_token} during the one-time subscription
 *       handshake.</li>
 * </ul>
 *
 * <p>All three values are validated at startup by {@link #validate()} (called
 * from {@code @PostConstruct}): a BLANK {@code appSecret} or
 * {@code verifyToken}, or a blank/unsupported {@code defaultRegion}, must
 * refuse to start the application. The {@code ${ENV_VAR:default}} placeholder default only
 * applies when the property is ABSENT — an explicitly empty environment
 * variable resolves to {@code ""}, and with a blank app secret every signed
 * POST would die with a 500 ({@code SecretKeySpec} rejects an empty key),
 * the exact response that makes the provider retry forever; with a blank
 * verify token anyone could satisfy the handshake with an empty
 * {@code hub.verify_token}. A region code libphonenumber does not know would
 * silently fail to canonicalize every LOCALLY stored phone (the realistic
 * Argentine admin format), turning every message into a {@code NotFound}.
 * Failing fast at boot turns all three into a visible
 * deployment error instead of a silent production hole.</p>
 */
@Component
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsappProperties {

	/**
	 * Meta app secret used to verify {@code X-Hub-Signature-256}. The default
	 * only serves local development and matches the simulator default in
	 * {@code odd/tasks/tools/whatsapp-simulator.mjs}.
	 */
	private String appSecret = "local-dev-secret";

	/**
	 * Token Meta must present as {@code hub.verify_token} in the GET
	 * handshake. The default only serves local development.
	 */
	private String verifyToken = "local-dev-verify-token";

	/**
	 * ISO 3166-1 alpha-2 region used to interpret phone values whose spelling
	 * carries no country code. Since T3c the stored value is always canonical
	 * E.164 digits (the shape CHECK rejects local spellings such as
	 * {@code 011 2233-4455}, and both compared sides carry a country code), so
	 * the region's remaining effect is on the LOOKUP side: it decides how a
	 * sender value lacking a country code is read. Defaulted to {@code AR}
	 * because the pilot target is Argentina; change it per environment with
	 * {@code WHATSAPP_DEFAULT_REGION}. Case- and whitespace-tolerant — the
	 * normalized spelling is the single one computed by
	 * {@link PhoneNumbers}, shared by validation and parsing.
	 */
	private String defaultRegion = "AR";

	public String getAppSecret() {
		return appSecret;
	}

	public void setAppSecret(String appSecret) {
		this.appSecret = appSecret;
	}

	public String getVerifyToken() {
		return verifyToken;
	}

	public void setVerifyToken(String verifyToken) {
		this.verifyToken = verifyToken;
	}

	public String getDefaultRegion() {
		return defaultRegion;
	}

	public void setDefaultRegion(String defaultRegion) {
		this.defaultRegion = defaultRegion;
	}

	/**
	 * Startup guard: refuses a blank {@code appSecret} or {@code verifyToken},
	 * or a blank/unsupported {@code defaultRegion}, with an exception naming
	 * the offending property. Public so the check is directly unit-testable
	 * without booting a Spring context.
	 */
	@PostConstruct
	public void validate() {
		requireNonBlank(appSecret, "whatsapp.app-secret");
		requireNonBlank(verifyToken, "whatsapp.verify-token");
		requireNonBlank(defaultRegion, "whatsapp.default-region");
		if (!PhoneNumbers.isSupportedRegion(defaultRegion)) {
			throw new IllegalStateException("whatsapp.default-region must be a supported ISO 3166-1 "
					+ "alpha-2 region code (got \"" + defaultRegion + "\"): an unsupported region would "
					+ "fail to canonicalize every locally stored phone, routing every message to "
					+ "not-found");
		}
	}

	private static void requireNonBlank(String value, String propertyName) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(propertyName
					+ " must not be blank: without it the WhatsApp webhook cannot verify origin "
					+ "(note that ${ENV_VAR:default} only applies when the variable is ABSENT; "
					+ "an explicitly empty variable resolves to \"\")");
		}
	}
}
