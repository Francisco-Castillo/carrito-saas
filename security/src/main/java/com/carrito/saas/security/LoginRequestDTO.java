package com.carrito.saas.security;

import lombok.Data;

@Data
public class LoginRequestDTO {
	
	private String username;
    private String password;
    private String restaurantSlug;

}
