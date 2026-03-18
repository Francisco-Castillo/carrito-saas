package com.carrito.saas.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.carrito.saas.repository.entity.BusinessUser;
import com.carrito.saas.repository.jpa.BusinessUserRepository;

@Service
public class SecurityService {

	private final BusinessUserRepository businessUserRepository;

	public SecurityService(BusinessUserRepository businessUserRepository) {
		this.businessUserRepository = businessUserRepository;
	}

	public Long getCurrentBusinessId() {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		String username = authentication.getName();

		BusinessUser bu = businessUserRepository.findByUserUsername(username)
				.orElseThrow(() -> new RuntimeException("Usuario no pertenece a un negocio"));

		return bu.getBusiness().getId();
	}

}
