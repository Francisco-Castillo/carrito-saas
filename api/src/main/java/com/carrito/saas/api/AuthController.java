package com.carrito.saas.api;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.security.JwtUtil;
import com.carrito.saas.security.LoginRequestDTO;
import com.carrito.saas.security.LoginResponseDTO;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthenticationManager authenticationManager;
	private final JwtUtil jwtUtil;

	public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil) {
		super();
		this.authenticationManager = authenticationManager;
		this.jwtUtil = jwtUtil;
	}

	@PostMapping("/login")
	public LoginResponseDTO login(@RequestBody LoginRequestDTO request) {

		authenticationManager
				.authenticate(new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

		String token = jwtUtil.generateToken(request.getUsername());

		return new LoginResponseDTO(token);
	}

}
