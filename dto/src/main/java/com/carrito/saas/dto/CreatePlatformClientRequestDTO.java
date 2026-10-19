package com.carrito.saas.dto;

import lombok.Data;

/**
 * Payload of {@code POST /api/platform/clients}: the operator registers a
 * CLIENT in one call — the business (the locale) and its owner user with a
 * working password (odd/tasks/plataforma-altas.md, S1/S4).
 *
 * <p>Fields are validated by hand in
 * {@code PlatformClientServiceImpl} (the platform keeps its own legible
 * Spanish messages; there is no Bean Validation dependency at the entity
 * layer and the DTO module has none wired to the controller advice).</p>
 */
@Data
public class CreatePlatformClientRequestDTO {

	private BusinessData business;

	private OwnerData owner;

	@Data
	public static class BusinessData {

		private String name;

		private String slug;

		/** Optional; when present it must be E.164-like digits (8-14). */
		private String phone;
	}

	@Data
	public static class OwnerData {

		private String username;

		private String email;

		/** Plaintext from the operator panel; stored bcrypt-hashed. */
		private String password;
	}
}
