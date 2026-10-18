package com.carrito.saas.service.impl;

import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;

/**
 * Resolves the business of a SINGLE-business deployment without a slug.
 *
 * <p>The public customer menu no longer carries the business slug in the URL:
 * the QR points at a plain {@code /menu}, so the slugless endpoints
 * ({@code GET /api/menu}, {@code POST /api/orders/menu}) must derive the
 * business themselves. The deployment contract is exactly one business —
 * this resolver returns it and fails closed otherwise:</p>
 *
 * <ul>
 *   <li>zero businesses → NOT_FOUND ("Local no encontrado"), the same error
 *       contract an unknown slug produces;</li>
 *   <li>more than one → NOT_FOUND with an explicit message, because guessing
 *       a business for a public customer endpoint would leak another
 *       tenant's menu.</li>
 * </ul>
 */
@Component
public class SingleBusinessResolver {

	private final BusinessRepository businessRepository;

	public SingleBusinessResolver(BusinessRepository businessRepository) {
		this.businessRepository = businessRepository;
	}

	/**
	 * @return the deployment's single business.
	 * @throws BusinessException NOT_FOUND when zero or multiple businesses exist.
	 */
	public Business requireSingle() {

		List<Business> all = businessRepository.findAll();

		if (all.size() == 1) {
			return all.get(0);
		}

		if (all.isEmpty()) {
			throw new BusinessException("Local no encontrado", ErrorType.NOT_FOUND);
		}

		throw new BusinessException(
				"Este enlace no indica un local y el sistema tiene más de uno cargado; "
						+ "no se puede determinar a qué local pertenece la carta",
				ErrorType.NOT_FOUND);
	}
}
