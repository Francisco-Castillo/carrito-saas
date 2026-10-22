package com.carrito.saas.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.ProductTrendDTO;
import com.carrito.saas.service.interfaces.IProductTrendService;

/**
 * Slug-scoped product trends for the panel dashboard. The frontend
 * ({@code frontend/src/pages/dashboard/api.ts}) already consumes these routes
 * through its slug-aware base URL; the legacy global routes
 * ({@code /api/trends/top-up|down}) stay untouched.
 *
 * <p>Authentication is enforced by the catch-all rule on
 * {@code /api/restaurants/**}: the panel KPIs must never be public just
 * because the slug is.</p>
 */
@RestController
@RequestMapping("/api/restaurants/{slug}/trends")
public class RestaurantTrendController {

	private final IProductTrendService productTrendService;

	public RestaurantTrendController(IProductTrendService productTrendService) {
		this.productTrendService = productTrendService;
	}

	@GetMapping("/top-up")
	public List<ProductTrendDTO> getTopUp(@PathVariable String slug,
			@RequestParam(defaultValue = "5") int limit) {
		return productTrendService.getTopUp(slug, limit);
	}

	@GetMapping("/top-down")
	public List<ProductTrendDTO> getTopDown(@PathVariable String slug,
			@RequestParam(defaultValue = "5") int limit) {
		return productTrendService.getTopDown(slug, limit);
	}

}
