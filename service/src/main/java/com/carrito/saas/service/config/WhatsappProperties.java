package com.carrito.saas.service.config;

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
 * <p>Both values are validated at startup by {@link #validate()} (called
 * from {@code @PostConstruct}): a BLANK value must refuse to start the
 * application. The {@code ${ENV_VAR:default}} placeholder default only
 * applies when the property is ABSENT — an explicitly empty environment
 * variable resolves to {@code ""}, and with a blank app secret every signed
 * POST would die with a 500 ({@code SecretKeySpec} rejects an empty key),
 * the exact response that makes the provider retry forever; with a blank
 * verify token anyone could satisfy the handshake with an empty
 * {@code hub.verify_token}. Failing fast at boot turns both into a visible
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

	/**
	 * Startup guard: refuses a blank {@code appSecret} or {@code verifyToken}
	 * with an exception naming the offending property. Public so the check is
	 * directly unit-testable without booting a Spring context.
	 */
	@PostConstruct
	public void validate() {
		requireNonBlank(appSecret, "whatsapp.app-secret");
		requireNonBlank(verifyToken, "whatsapp.verify-token");
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
