package com.carrito.saas.dto;

import lombok.Data;

/**
 * Response of the platform client endpoints ({@code POST}, {@code GET} and
 * {@code PUT} {@code /api/platform/clients}): what the operator panel lists
 * and what the alta/edit answer — the locale identity plus its owner account
 * (never the password). {@code active} carries the logical-deactivation
 * state (odd/tasks/plataforma-editar-baja.md, S3).
 */
@Data
public class PlatformClientDTO {

	private Long businessId;

	private String businessName;

	private String slug;

	private String phone;

	private String ownerUsername;

	private String ownerEmail;

	private Boolean active;
}
