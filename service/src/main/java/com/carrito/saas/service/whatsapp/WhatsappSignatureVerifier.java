package com.carrito.saas.service.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.carrito.saas.service.config.WhatsappProperties;

/**
 * Verifies the origin of inbound Meta Cloud API webhook calls.
 *
 * <p>Two independent constant-time checks live here:</p>
 * <ul>
 *   <li>{@link #verifySignature(byte[], String)} — HMAC-SHA256 of the RAW
 *       request body with the app secret, compared against the
 *       {@code X-Hub-Signature-256: sha256=&lt;hex&gt;} header. The comparison
 *       uses {@link MessageDigest#isEqual}, never {@code String.equals}. The
 *       HMAC must be computed over the exact bytes Meta sent: re-serializing
 *       the JSON does not reproduce the original bytes (key order, whitespace,
 *       number formatting), which is why the controller verifies before
 *       parsing.</li>
 *   <li>{@link #verifyVerifyToken(String)} — constant-time check of the
 *       {@code hub.verify_token} presented during the one-time subscription
 *       handshake.</li>
 * </ul>
 */
@Component
public class WhatsappSignatureVerifier {

	private static final String HMAC_SHA256 = "HmacSHA256";
	private static final String SIGNATURE_PREFIX = "sha256=";

	private final WhatsappProperties properties;

	public WhatsappSignatureVerifier(WhatsappProperties properties) {
		this.properties = properties;
	}

	/**
	 * Verifies {@code signatureHeader} against the HMAC-SHA256 of the raw
	 * request bytes. Returns {@code false} for a missing body, a missing or
	 * malformed header, or any mismatch.
	 */
	public boolean verifySignature(byte[] rawBody, String signatureHeader) {
		if (rawBody == null || rawBody.length == 0
				|| signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
			return false;
		}
		String presentedHex = signatureHeader.substring(SIGNATURE_PREFIX.length()).trim().toLowerCase();
		byte[] provided;
		try {
			provided = HexFormat.of().parseHex(presentedHex);
		} catch (IllegalArgumentException notHex) {
			return false;
		}
		return MessageDigest.isEqual(hmacSha256(rawBody), provided);
	}

	/**
	 * Constant-time check of the {@code hub.verify_token} presented in the
	 * handshake against the configured token.
	 */
	public boolean verifyVerifyToken(String presentedToken) {
		if (presentedToken == null) {
			return false;
		}
		return MessageDigest.isEqual(
				properties.getVerifyToken().getBytes(StandardCharsets.UTF_8),
				presentedToken.getBytes(StandardCharsets.UTF_8));
	}

	private byte[] hmacSha256(byte[] data) {
		try {
			Mac mac = Mac.getInstance(HMAC_SHA256);
			mac.init(new SecretKeySpec(properties.getAppSecret().getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
			return mac.doFinal(data);
		} catch (Exception cannotHappen) {
			throw new IllegalStateException("HMAC-SHA256 is unavailable in this JVM", cannotHappen);
		}
	}
}
