package com.carrito.saas.dto;

import lombok.Data;

/**
 * Payload of {@code POST /api/platform/clients/{id}/users}: the operator
 * registers a user for a client's locale with a role and a working password
 * (S3 of odd/tasks/plataforma-usuarios.md). Same validations as the
 * business-side user alta.
 */
@Data
public class CreatePlatformUserRequestDTO {

	private String username;

	private String email;

	private String password;

	/** Role NAME from the roles catalogue (e.g. KITCHEN, PEDIDOS, OWNER). */
	private String role;
}
