package com.carrito.saas.security;

import lombok.Data;

@Data
public class LoginResponseDTO {
	private String token;
	private String restaurantSlug;
	private Long businessId;
	private String role;
	private String username;
	private Long expiresAt;

	public LoginResponseDTO(String token) {
		super();
		this.token = token;
	}

	public LoginResponseDTO(String token, String restaurantSlug, Long businessId, String role, String username,
			Long expiresAt) {
		this.token = token;
		this.restaurantSlug = restaurantSlug;
		this.businessId = businessId;
		this.role = role;
		this.username = username;
		this.expiresAt = expiresAt;
	}

}
