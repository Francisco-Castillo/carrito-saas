package com.carrito.saas.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.service.interfaces.IProductService;

@RestController
@RequestMapping("/api")

public class ProductController {

	private final IProductService productService;

	public ProductController(IProductService productService) {
		this.productService = productService;
	}

	@GetMapping("/restaurants/{restaurantId}/products")
	public List<ProductDTO> getProductsByRestaurant(@PathVariable Long restaurantId) {
		return productService.getProductsByRestaurant(restaurantId);
	}

}
