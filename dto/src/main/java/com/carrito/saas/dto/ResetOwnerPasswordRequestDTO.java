package com.carrito.saas.dto;

import lombok.Data;

/**
 * Payload of {@code POST /api/platform/clients/{id}/password}: the NEW
 * password for the client's owner (S4 of odd/tasks/plataforma-editar-baja.md)
 * — for when the client forgot it. Stored bcrypt-hashed; same ≥8 rule as
 * the alta.
 */
@Data
public class ResetOwnerPasswordRequestDTO {

	private String password;
}
