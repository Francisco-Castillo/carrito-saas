package com.carrito.saas.dto;

import lombok.Data;

/**
 * Response of the platform client endpoints ({@code POST} and {@code GET}
 * {@code /api/platform/clients}): what the operator panel lists and what the
 * alta answers — the locale identity plus its owner account (never the
 * password).
 */
@Data
public class PlatformClientDTO {

	private Long businessId;

	private String businessName;

	private String slug;

	private String phone;

	private String ownerUsername;

	private String ownerEmail;
}
