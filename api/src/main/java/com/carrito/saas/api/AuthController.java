package com.carrito.saas.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.security.ISecurityService;
import com.carrito.saas.security.LoginRequestDTO;
import com.carrito.saas.security.LoginResponseDTO;


@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final ISecurityService iSecurityService;

	public AuthController(ISecurityService iSecurityService) {
		this.iSecurityService = iSecurityService;
	}

	@PostMapping("/login")
	public LoginResponseDTO login(@RequestBody LoginRequestDTO request) {

		return iSecurityService.login(request);
	}

}
