package com.carrito.saas.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.CreatePlatformClientRequestDTO;
import com.carrito.saas.dto.PlatformClientDTO;
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
}
