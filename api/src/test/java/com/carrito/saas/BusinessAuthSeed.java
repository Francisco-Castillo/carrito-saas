package com.carrito.saas;

import java.util.Set;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessHours;
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

	/**
	 * Semana completa abierta (00:00–12:00 y 12:00–00:00 cruzando la
	 * medianoche) para un negocio: los fixtures de suites que crean pedidos la
	 * necesitan porque createOrder respeta el horario efectivo del local
	 * (negocio sin filas = cerrado, regla del badge). Sin hueco de borde: el
	 * turno de la tarde cruza la medianoche, así que cada minuto del día
	 * (y la madrugada del día siguiente) está cubierto. Idempotente: los
	 * fixtures siembran por test, y uq_business_hours_business_day_position
	 * rechazaría un segundo sembrado.
	 */
	static void seedAlwaysOpenWeek(com.carrito.saas.repository.jpa.BusinessHoursRepository businessHoursRepository,
			BusinessRepository businessRepository, long businessId) {
		if (!businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(businessId).isEmpty()) {
			return;
		}
		Business business = businessRepository.findById(businessId).orElseThrow();
		for (int day = 1; day <= 7; day++) {
			seedShift(businessHoursRepository, business, day, 0, java.time.LocalTime.of(0, 0), java.time.LocalTime.of(12, 0));
			seedShift(businessHoursRepository, business, day, 1, java.time.LocalTime.of(12, 0), java.time.LocalTime.of(0, 0));
		}
	}

	private static void seedShift(com.carrito.saas.repository.jpa.BusinessHoursRepository businessHoursRepository,
			Business business, int dayOfWeek, int position, java.time.LocalTime opensAt, java.time.LocalTime closesAt) {
		BusinessHours shift = new BusinessHours();
		shift.setBusiness(business);
		shift.setDayOfWeek(dayOfWeek);
		shift.setPosition(position);
		shift.setOpensAt(opensAt);
		shift.setClosesAt(closesAt);
		businessHoursRepository.saveAndFlush(shift);
	}

	static AuthenticatedBusiness seedOwner(BusinessRepository businessRepository, UserRepository userRepository,
			RoleRepository roleRepository, BusinessUserRepository businessUserRepository, JwtUtil jwtUtil,
			long businessId, String slug, String username) {

		return seedUserWithRole(businessRepository, userRepository, roleRepository, businessUserRepository, jwtUtil,
				businessId, slug, username, "OWNER");
	}

	/**
	 * Same fixture with an explicit role name: the role row is reused when it
	 * already exists (shared dev databases keep seeded roles; the unique
	 * uq_roles_name would reject a second insert) and created on a fresh
	 * database. The user gets the role both as a direct user-role and in the
	 * business-user link, mirroring what the real seed does.
	 */
	static AuthenticatedBusiness seedUserWithRole(BusinessRepository businessRepository, UserRepository userRepository,
			RoleRepository roleRepository, BusinessUserRepository businessUserRepository, JwtUtil jwtUtil,
			long businessId, String slug, String username, String roleName) {

		Business business = seedBusiness(businessRepository, businessId, slug);

		Role role = roleRepository.findByName(roleName)
				.orElseGet(() -> {
					Role created = new Role();
					created.setName(roleName);
					return roleRepository.saveAndFlush(created);
				});

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
