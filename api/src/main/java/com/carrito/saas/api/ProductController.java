package com.carrito.saas.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.service.interfaces.IProductService;

@RestController
@RequestMapping("/api")

public class ProductController {

	private final IProductService productService;
	private final BusinessRepository businessRepository;

	
	public ProductController(IProductService productService, BusinessRepository businessRepository) {
		super();
		this.productService = productService;
		this.businessRepository = businessRepository;
	}

	@GetMapping("/restaurants/{restaurantId}/products")
	public List<ProductDTO> getProductsByRestaurant(@PathVariable Long restaurantId) {
		return productService.getProductsByRestaurant(restaurantId);
	}
	
	@GetMapping("/restaurants/slug/{slug}/products")
	public List<ProductDTO> getProductsByRestaurantSlug(@PathVariable String slug) {

	    Business restaurant = businessRepository
	        .findBySlug(slug)
	        .orElseThrow(() -> new RuntimeException("Restaurant not found"));

	    return productService.getProductsByRestaurant(restaurant.getId());
	}

}
