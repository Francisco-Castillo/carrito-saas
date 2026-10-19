package com.carrito.saas.dto;

import lombok.Data;

/**
 * Payload of {@code PUT /api/platform/clients/{id}/users/{username}}:
 * partial — the role to set and/or the reactivation flag (S3 of
 * odd/tasks/plataforma-usuarios.md). Fields left null stay untouched.
 */
@Data
public class UpdatePlatformUserRequestDTO {

	private String role;

	private Boolean active;
}
