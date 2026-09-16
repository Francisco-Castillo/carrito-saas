package com.carrito.saas;

import java.util.Set;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessUser;
import com.carrito.saas.repository.entity.Role;
import com.carrito.saas.repository.entity.User;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * Shared fixture for tests that exercise endpoints behind the real JWT filter
 * chain: seeds a business, an owner user, the user-business link, and mints a
 * real JWT for the {@code Authorization: Bearer} header.
 *
 * <p>Callers run inside {@code @Transactional} tests, so every seeded row is
 * rolled back at test end.</p>
 */
final class BusinessAuthSeed {

	/**
	 * @param bearerToken raw token WITHOUT the "Bearer " prefix
	 */
	record AuthenticatedBusiness(Long businessId, String slug, String username, String bearerToken) {

		String authorizationHeader() {
			return "Bearer " + bearerToken;
		}
	}

	private BusinessAuthSeed() {
	}

	static Business seedBusiness(BusinessRepository businessRepository, long id, String slug) {
		Business business = new Business();
		business.setId(id);
		business.setName("Business " + slug);
		business.setSlug(slug);
		return businessRepository.saveAndFlush(business);
	}

	static AuthenticatedBusiness seedOwner(BusinessRepository businessRepository, UserRepository userRepository,
			RoleRepository roleRepository, BusinessUserRepository businessUserRepository, JwtUtil jwtUtil,
			long businessId, String slug, String username) {

		Business business = seedBusiness(businessRepository, businessId, slug);

		Role role = new Role();
		role.setName("OWNER");
		role = roleRepository.saveAndFlush(role);

		User user = new User();
		user.setUsername(username);
		user.setPassword("test-password");
		user.setRoles(Set.of(role));
		user = userRepository.saveAndFlush(user);

		BusinessUser businessUser = new BusinessUser();
		businessUser.setUser(user);
		businessUser.setBusiness(business);
		businessUser.setRole(role);
		businessUserRepository.saveAndFlush(businessUser);

		return new AuthenticatedBusiness(businessId, slug, username, jwtUtil.generateToken(username, businessId));
	}
}
