package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.CreatePlatformClientRequestDTO;
import com.carrito.saas.dto.PlatformClientDTO;
import com.carrito.saas.dto.UpdatePlatformClientRequestDTO;

/**
 * Platform-side client management: the operator registers (owner + locale),
 * edits and deactivates clients (odd/tasks/plataforma-editar-baja.md, S1).
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

	/**
	 * Partial edit of the SAFE fields (name, phone, ownerEmail) and optional
	 * reactivation/deactivation ({@code active}). Unknown id answers 404; a
	 * colliding owner email 409; a malformed phone 400. The slug NEVER moves
	 * — it is the public identity (printed QRs, menu URLs).
	 */
	PlatformClientDTO editClient(Long businessId, UpdatePlatformClientRequestDTO request);

	/**
	 * LOGICAL deactivation (S3): the row, the owner account and the
	 * membership survive (active=false); history is untouched — physical
	 * DELETE is forbidden. Idempotent: deactivating an already-deactivated
	 * client answers 200 without changes; unknown id answers 404.
	 */
	PlatformClientDTO deactivateClient(Long businessId);

	/**
	 * Resets the OWNER's password (S4 — the client forgot it): the new
	 * password is stored bcrypt-hashed and the owner logs in with it right
	 * after. ≥8 rule same as the alta; unknown id answers 404, a business
	 * without an owner answers 404 (nothing to reset), shorter than 8
	 * answers 400 and leaves the stored hash untouched.
	 */
	PlatformClientDTO resetOwnerPassword(Long businessId, String newPassword);
}
