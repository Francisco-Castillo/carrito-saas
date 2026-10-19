package com.carrito.saas.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.CreatePlatformClientRequestDTO;
import com.carrito.saas.dto.PlatformClientDTO;
import com.carrito.saas.dto.ResetOwnerPasswordRequestDTO;
import com.carrito.saas.dto.UpdatePlatformClientRequestDTO;
import com.carrito.saas.service.interfaces.IPlatformClientService;

/**
 * Platform operator API (odd/tasks/plataforma-altas.md): the alta of clients
 * — owner user + locale — in one call. The privilege barrier for
 * {@code /api/platform/**} lives in {@code SecurityConfig}
 * (ROLE_PLATFORM_ADMIN only; see PlatformPrivilegeHttpTests).
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformClientController {

	private final IPlatformClientService platformClientService;

	public PlatformClientController(IPlatformClientService platformClientService) {
		this.platformClientService = platformClientService;
	}

	@PostMapping("/clients")
	public ResponseEntity<PlatformClientDTO> createClient(
			@RequestBody CreatePlatformClientRequestDTO request) {

		PlatformClientDTO created = platformClientService.createClient(request);

		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@GetMapping("/clients")
	public java.util.List<PlatformClientDTO> listClients() {

		return platformClientService.listClients();
	}

	@GetMapping("/clients/{id}/users")
	public java.util.List<com.carrito.saas.dto.PlatformUserDTO> listUsers(@PathVariable("id") Long id) {

		return platformClientService.listUsers(id);
	}

	@PostMapping("/clients/{id}/users")
	public ResponseEntity<com.carrito.saas.dto.PlatformUserDTO> createUser(@PathVariable("id") Long id,
			@RequestBody com.carrito.saas.dto.CreatePlatformUserRequestDTO request) {

		return ResponseEntity.status(HttpStatus.CREATED)
				.body(platformClientService.createUser(id, request));
	}

	@PutMapping("/clients/{id}/users/{username}")
	public com.carrito.saas.dto.PlatformUserDTO editUser(@PathVariable("id") Long id,
			@PathVariable("username") String username,
			@RequestBody com.carrito.saas.dto.UpdatePlatformUserRequestDTO request) {

		return platformClientService.editUser(id, username, request);
	}

	@DeleteMapping("/clients/{id}/users/{username}")
	public com.carrito.saas.dto.PlatformUserDTO deactivateUser(@PathVariable("id") Long id,
			@PathVariable("username") String username) {

		// Baja LÓGICA (no physical): el row sobrevive con active=false.
		return platformClientService.deactivateUser(id, username);
	}

	@PutMapping("/clients/{id}")
	public PlatformClientDTO editClient(@PathVariable("id") Long id,
			@RequestBody UpdatePlatformClientRequestDTO request) {

		return platformClientService.editClient(id, request);
	}

	@DeleteMapping("/clients/{id}")
	public PlatformClientDTO deactivateClient(@PathVariable("id") Long id) {

		// Baja LÓGICA (no physical): el row sobrevive con active=false.
		return platformClientService.deactivateClient(id);
	}

	@PostMapping("/clients/{id}/password")
	public PlatformClientDTO resetOwnerPassword(@PathVariable("id") Long id,
			@RequestBody ResetOwnerPasswordRequestDTO request) {

		// Reset de la clave del dueño (S4): bcrypt, igual que el alta.
		return platformClientService.resetOwnerPassword(id, request.getPassword());
	}
}
