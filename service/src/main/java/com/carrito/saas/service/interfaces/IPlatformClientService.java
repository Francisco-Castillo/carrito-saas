package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.CreatePlatformClientRequestDTO;
import com.carrito.saas.dto.CreatePlatformUserRequestDTO;
import com.carrito.saas.dto.PlatformClientDTO;
import com.carrito.saas.dto.PlatformUserDTO;
import com.carrito.saas.dto.UpdatePlatformClientRequestDTO;
import com.carrito.saas.dto.UpdatePlatformUserRequestDTO;

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

	/**
	 * Users of a client's locale as the operator sees them (S2/S3 of
	 * odd/tasks/plataforma-usuarios.md): username, email, role and the
	 * deactivation state. Unknown client answers 404.
	 */
	List<PlatformUserDTO> listUsers(Long businessId);

	/**
	 * Registers a user for a client's locale with a role (S3): bcrypt
	 * password, ACTIVE account, membership in the role. 201 with the user;
	 * 404 unknown client; 409 duplicate username/email; 400 unknown role,
	 * blank fields or a password shorter than 8.
	 */
	PlatformUserDTO createUser(Long businessId, CreatePlatformUserRequestDTO request);

	/**
	 * Partial edit of a locale user (S3): change the role and/or reactivate.
	 * The PROTECTION rule (S4): neither demoting nor deactivating may leave
	 * the locale without an ACTIVE owner — 409 with a legible message and
	 * nothing changes. Unknown user answers 404, unknown role 400.
	 */
	PlatformUserDTO editUser(Long businessId, String username, UpdatePlatformUserRequestDTO request);

	/**
	 * LOGICAL deactivation of a locale user (S4): the row and the membership
	 * survive (active=false); history untouched. The locale must keep an
	 * ACTIVE owner — deactivating the last one answers 409. Idempotent;
	 * unknown user answers 404.
	 */
	PlatformUserDTO deactivateUser(Long businessId, String username);
}
