package com.carrito.saas.dto;

import lombok.Data;

/**
 * Payload of {@code PUT /api/platform/clients/{id}}: partial edit of an
 * existing client — the SAFE fields only (S2 of
 * odd/tasks/plataforma-editar-baja.md). Fields left null stay untouched;
 * {@code slug} is deliberately absent (it is the public identity: printed
 * QR codes and menu URLs) and {@code ownerUsername} is not editable either.
 * {@code active} reactivates (or deactivates) the client when sent.
 */
@Data
public class UpdatePlatformClientRequestDTO {

	private String name;

	private String phone;

	private String ownerEmail;

	private Boolean active;
}
