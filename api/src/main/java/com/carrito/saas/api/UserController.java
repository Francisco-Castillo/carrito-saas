package com.carrito.saas.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.CreateUserRequestDTO;
import com.carrito.saas.dto.UserResponseDTO;
import com.carrito.saas.service.interfaces.IUserService;

@RestController
@RequestMapping("/api/restaurants/{slug}/users")
public class UserController {

	private final IUserService userService;

	public UserController(IUserService userService) {
		super();
		this.userService = userService;
	}

	@PostMapping
	public UserResponseDTO createUser(@PathVariable String slug, @RequestBody CreateUserRequestDTO request) {
		return userService.createUser(slug, request);
	}

}
