package com.carrito.saas.service.interfaces;

import com.carrito.saas.dto.BusinessProfileDTO;

/**
 * Storefront profile of the authenticated owner's business. Both methods are
 * scoped to the JWT ({@code ISecurityService#getCurrentBusinessId()}); there
 * is deliberately no id or slug parameter.
 *
 * <p>Opening-hours convention: {@code closesAt < opensAt} means the venue
 * closes past midnight (the closing time belongs to the small hours of the
 * NEXT day, e.g. Friday 11:00 → 00:30); {@code closesAt > opensAt} is a
 * same-day close. Only {@code closesAt == opensAt} is rejected, as ambiguous
 * (zero hours or 24?).</p>
 */
public interface IBusinessProfileService {

	BusinessProfileDTO getProfile();

	BusinessProfileDTO updateProfile(BusinessProfileDTO dto);

	/**
	 * Reemplaza el logo del negocio del JWT con una imagen subida (multipart
	 * ya leída a bytes): guarda el archivo, borra el anterior si era del
	 * storage propio y devuelve el perfil actualizado.
	 */
	BusinessProfileDTO updateLogoImage(byte[] data, String contentType);

	/** Quita el logo propio del negocio del JWT; si no tiene, no cambia nada. */
	BusinessProfileDTO clearLogoImage();

	/** Igual que {@link #updateLogoImage(byte[], String)} para el banner. */
	BusinessProfileDTO updateBannerImage(byte[] data, String contentType);

	/** Igual que {@link #clearLogoImage()} para el banner. */
	BusinessProfileDTO clearBannerImage();

}
