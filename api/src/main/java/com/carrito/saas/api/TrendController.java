package com.carrito.saas.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.ProductTrendDTO;
import com.carrito.saas.service.interfaces.IProductTrendService;
import com.carrito.saas.service.usecase.IProductTrendUseCase;

@RestController
@RequestMapping("/api/trends")
public class TrendController {

	private final IProductTrendUseCase useCase;
	private final IProductTrendService productTrendService;

	public TrendController(IProductTrendUseCase useCase, IProductTrendService productTrendService) {
		this.useCase = useCase;
		this.productTrendService = productTrendService;
	}

	@PostMapping("/run")
	public void run() {
		useCase.execute();
	}

	// TOP 5 EN SUBIDA
	@GetMapping("/top-up")
	public ResponseEntity<List<ProductTrendDTO>> getTopUp(@RequestParam(defaultValue = "5") int limit) {

		List<ProductTrendDTO> result = productTrendService.getTopUp(limit);

		return ResponseEntity.ok(result);
	}

	// TOP 5 EN CAÍDA
	@GetMapping("/top-down")
	public ResponseEntity<List<ProductTrendDTO>> getTopDown(@RequestParam(defaultValue = "5") int limit) {

		List<ProductTrendDTO> result = productTrendService.getTopDown(limit);

		return ResponseEntity.ok(result);
	}

}
