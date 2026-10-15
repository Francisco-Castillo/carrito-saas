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

}
