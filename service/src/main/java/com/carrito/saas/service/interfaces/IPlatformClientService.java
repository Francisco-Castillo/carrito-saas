package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.CreatePlatformClientRequestDTO;
import com.carrito.saas.dto.PlatformClientDTO;

/**
 * Platform-side client management: the operator registers clients (owner +
 * locale) and lists them (odd/tasks/plataforma-altas.md, S1).
 */
public interface IPlatformClientService {

	/**
	 * Atomic alta: creates the business, the owner user (bcrypt password)
	 * and the OWNER membership in one transaction. Duplicates answer 409
	 * CONFLICT with a legible Spanish message and leave no trace; malformed
	 * input answers 400 VALIDATION.
	 */
	PlatformClientDTO createClient(CreatePlatformClientRequestDTO request);

	/** Every business of the platform, oldest first; the owner account when
	 * the business has one (null otherwise). */
	List<PlatformClientDTO> listClients();
}
