package com.carrito.saas.service.interfaces;

import com.carrito.saas.dto.CreatePlatformClientRequestDTO;
import com.carrito.saas.dto.PlatformClientDTO;

/**
 * Platform-side client registration: the operator panel registers clients
 * (owner + locale) in one call (odd/tasks/plataforma-altas.md, S1). The
 * listing arrives in its own task (T3).
 */
public interface IPlatformClientService {

	/**
	 * Atomic alta: creates the business, the owner user (bcrypt password)
	 * and the OWNER membership in one transaction. Duplicates answer 409
	 * CONFLICT with a legible Spanish message and leave no trace; malformed
	 * input answers 400 VALIDATION.
	 */
	PlatformClientDTO createClient(CreatePlatformClientRequestDTO request);
}
