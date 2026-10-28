package com.carrito.saas.security;

public interface ISecurityService {

	LoginResponseDTO login(LoginRequestDTO request);
	
	Long getCurrentBusinessId();

}
