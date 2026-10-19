package com.carrito.saas.dto;

import lombok.Data;

/**
 * A user of a client's locale as the operator panel sees it: account
 * identity, role in that locale and the logical-deactivation state
 * (odd/tasks/plataforma-usuarios.md). Never carries the password.
 */
@Data
public class PlatformUserDTO {

	private String username;

	private String email;

	private String role;

	private Boolean active;
}
