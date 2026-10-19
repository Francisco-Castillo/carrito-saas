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

import java.util.List;

import com.carrito.saas.repository.jpa.BusinessUserRepository;

@Service
public class SecurityService implements ISecurityService {

	private final AuthenticationManager authenticationManager;
	private final BusinessUserRepository businessUserRepository;
	private final JwtUtil jwtUtil;

	public SecurityService(AuthenticationManager authenticationManager, BusinessUserRepository businessUserRepository,
			JwtUtil jwtUtil) {
		super();
		this.authenticationManager = authenticationManager;
		this.businessUserRepository = businessUserRepository;
		this.jwtUtil = jwtUtil;
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
	 * deduce de la membresía: exactamente una → esa; cero → 403; más de una →
	 * 400 pidiendo el negocio, porque todavía no existe selector en el login.
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
			throw new BusinessException("El usuario no pertenece a ningún negocio", ErrorType.FORBIDDEN);
		}

		if (memberships.size() > 1) {
			throw new BusinessException("El usuario pertenece a varios negocios. Debe indicar el negocio",
					ErrorType.VALIDATION);
		}

		return memberships.get(0);
	}

	@Override
	public Long getCurrentBusinessId() {

		Authentication auth = SecurityContextHolder.getContext().getAuthentication();

		String token = (String) auth.getCredentials(); // lo recuperás

		return jwtUtil.extractBusinessId(token); // acá lo usás
	}

}
