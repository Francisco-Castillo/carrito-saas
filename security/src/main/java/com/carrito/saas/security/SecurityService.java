package com.carrito.saas.security;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.carrito.saas.repository.entity.BusinessUser;

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

		// 1. Autenticación
		authenticationManager
				.authenticate(new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

		// 2. Relación user-business
		BusinessUser bu = businessUserRepository
				.findByUserUsernameAndBusinessSlug(request.getUsername(), request.getRestaurantSlug())
				.orElseThrow(() -> new RuntimeException("Usuario no pertenece a este negocio"));

		Long businessId = bu.getBusiness().getId();
		String role = bu.getRole().getName();
		String username = bu.getUser().getUsername();

		// 3. Token
		String token = jwtUtil.generateToken(username, businessId);

		long expiresAt = System.currentTimeMillis() + jwtUtil.getExpirationMillis();

		// 4. Response PRO
		return new LoginResponseDTO(token, bu.getBusiness().getSlug(), businessId, role, username, expiresAt);
	}

	@Override
	public Long getCurrentBusinessId() {

		Authentication auth = SecurityContextHolder.getContext().getAuthentication();

		String token = (String) auth.getCredentials(); // lo recuperás

		return jwtUtil.extractBusinessId(token); // acá lo usás
	}

}
