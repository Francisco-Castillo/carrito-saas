package com.carrito.saas.security;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.BusinessUser;
import com.carrito.saas.repository.entity.Branch;
import com.carrito.saas.repository.entity.Role;
import com.carrito.saas.repository.entity.User;

import java.util.List;

import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.BranchRepository;
import com.carrito.saas.repository.jpa.UserRepository;

@Service
public class SecurityService implements ISecurityService {

	private final AuthenticationManager authenticationManager;
	private final BusinessUserRepository businessUserRepository;
	private final UserRepository userRepository;
	private final JwtUtil jwtUtil;
	private final BranchRepository branchRepository;

	public SecurityService(AuthenticationManager authenticationManager, BusinessUserRepository businessUserRepository,
			UserRepository userRepository, JwtUtil jwtUtil, BranchRepository branchRepository) {
		super();
		this.authenticationManager = authenticationManager;
		this.businessUserRepository = businessUserRepository;
		this.userRepository = userRepository;
		this.jwtUtil = jwtUtil;
		this.branchRepository = branchRepository;
	}

	@Override
	public LoginResponseDTO login(LoginRequestDTO request) {

		// 1. Autenticación. AuthenticationException (contraseña incorrecta,
		// usuario inexistente, cuenta bloqueada) NO debe escapar como 500: el
		// cliente se equivocó y el 401 es honesto. El mensaje es único y
		// neutro a propósito — no revela si el usuario existe (Spring ya
		// oculta el UsernameNotFoundException como BadCredentials).
		try {
			authenticationManager
					.authenticate(new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
		} catch (AuthenticationException e) {
			throw new BusinessException("Usuario o contraseña incorrectos", ErrorType.UNAUTHORIZED);
		}

		// 2. Relación user-business. Acá las credenciales YA son válidas, así
		// que nombrar el motivo no filtra nada: 403, no 401 ni 500.
		BusinessUser bu = resolveMembership(request);

		// 2-bis. Operador de plataforma (odd/tasks/plataforma-altas.md, ahora
		// bajo el modelo de simplificacion-roles.md S5): el operador NO
		// pertenece a ningún negocio, y la membresía era el único camino al
		// token — quedaba afuera con el 403 de "no pertenece a ningún
		// negocio". El branch solo alcanza a usuarios SIN membresía que tienen
		// el rol ADMIN en user_roles (el rol exclusivo de la plataforma desde
		// que PLATFORM_ADMIN dejó de existir); un usuario de negocio conserva
		// el comportamiento de siempre.
		if (bu == null) {
			return platformLogin(request.getUsername());
		}

		Long businessId = bu.getBusiness().getId();
		String role = bu.getRole().getName();
		String username = bu.getUser().getUsername();

		// 3. Token
		String token = jwtUtil.generateToken(username, businessId);

		long expiresAt = System.currentTimeMillis() + jwtUtil.getExpirationMillis();

		// 4. Response PRO
		return new LoginResponseDTO(token, bu.getBusiness().getSlug(), businessId, role, username, expiresAt);
	}

	/**
	 * Resuelve la membresía que alimenta el token. Con un slug explícito en el
	 * pedido manda el slug (comportamiento de siempre, hacia atrás compatible
	 * y a prueba de multi-negocio). Sin slug — login orgánico — el negocio se
	 * deduce de la membresía: exactamente una → esa; cero → null (el branch
	 * de plataforma del login decide con el rol); más de una → 400 pidiendo
	 * el negocio, porque todavía no existe selector en el login.
	 */
	private BusinessUser resolveMembership(LoginRequestDTO request) {

		String slug = request.getRestaurantSlug();

		if (slug != null && !slug.isBlank()) {
			return businessUserRepository
					.findByUserUsernameAndBusinessSlug(request.getUsername(), slug)
					.orElseThrow(() -> new BusinessException("El usuario no pertenece a este negocio", ErrorType.FORBIDDEN));
		}

		List<BusinessUser> memberships = businessUserRepository.findByUserUsername(request.getUsername());

		if (memberships.isEmpty()) {
			// No es necesariamente un error: el operador de plataforma no tiene
			// membresía por diseño. El branch de arriba decide con el rol.
			return null;
		}

		if (memberships.size() > 1) {
			throw new BusinessException("El usuario pertenece a varios negocios. Debe indicar el negocio",
					ErrorType.VALIDATION);
		}

		return memberships.get(0);
	}

	/**
	 * Login del operador de plataforma: sin negocio, sin slug, sin membresía.
	 * El token sale SIN el claim businessId (el JwtFilter autentica tokens sin
	 * claim y no fija TenantContext), y el rol es ADMIN — el rol exclusivo de
	 * la plataforma (simplificacion-roles.md, S2/S5). Un usuario sin membresía
	 * y sin ADMIN sigue recibiendo el 403 de siempre.
	 */
	private LoginResponseDTO platformLogin(String username) {

		User user = userRepository.findByUsername(username)
				.orElseThrow(() -> new BusinessException("Usuario o contraseña incorrectos", ErrorType.UNAUTHORIZED));

		Role platformRole = user.getRoles().stream()
				.filter(role -> "ADMIN".equals(role.getName()))
				.findFirst()
				.orElseThrow(() -> new BusinessException("El usuario no pertenece a ningún negocio", ErrorType.FORBIDDEN));

		String token = jwtUtil.generateToken(username, null);

		long expiresAt = System.currentTimeMillis() + jwtUtil.getExpirationMillis();

		return new LoginResponseDTO(token, null, null, platformRole.getName(), username, expiresAt);
	}

	@Override
	public Long getCurrentBusinessId() {

		Authentication auth = SecurityContextHolder.getContext().getAuthentication();

		String token = (String) auth.getCredentials(); // lo recuperás

		return jwtUtil.extractBusinessId(token); // acá lo usás
	}

	@Override
	public Long getCurrentBranchId() {

		Authentication auth = SecurityContextHolder.getContext().getAuthentication();

		String token = (String) auth.getCredentials();

		// 1. El claim manda: un token emitido por select/{id} ya fija la
		// sucursal y no se re-resuelve (el dueño puede cambiar de sucursal
		// aunque el negocio siga teniendo varias).
		Long branchId = jwtUtil.extractBranchId(token);
		if (branchId != null) {
			return branchId;
		}

		Long businessId = jwtUtil.extractBusinessId(token);
		if (businessId == null) {
			// Operador de plataforma: no opera sucursales de ningún negocio.
			throw new BusinessException("El operador de plataforma no tiene sucursales", ErrorType.FORBIDDEN);
		}

		// 2. Fallback para tokens sin claim (login viejo, sesión ya abierta):
		// una sola sucursal activa se resuelve sola; ninguna → null (vista a
		// nivel negocio, compatibilidad pre-multi-sucursal); varias → 400
		// pidiendo selección explícita, NUNCA una vista mezclada de dos
		// sucursales (mismo criterio que el login orgánico: cero → resolver,
		// varias → pedir el dato).
		List<Branch> activas = branchRepository.findByBusinessIdAndActiveTrueOrderByIdAsc(businessId);

		if (activas.size() == 1) {
			return activas.get(0).getId();
		}

		if (activas.isEmpty()) {
			return null;
		}

		throw new BusinessException("Seleccioná una sucursal para continuar", ErrorType.VALIDATION);
	}

}
