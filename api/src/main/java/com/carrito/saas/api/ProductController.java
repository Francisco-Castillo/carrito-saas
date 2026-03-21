package com.carrito.saas.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.BusinessDTO;
import com.carrito.saas.dto.ProductCreateDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.service.interfaces.IProductService;
import com.carrito.saas.service.mapper.interfaces.IBusinessMapper;

@RestController
@RequestMapping("/api")
public class ProductController {

	private final IProductService productService;
	private final BusinessRepository businessRepository;
	private final IBusinessMapper iBusinessMapper;

	public ProductController(IProductService productService, BusinessRepository businessRepository,
			IBusinessMapper iBusinessMapper) {
		super();
		this.productService = productService;
		this.businessRepository = businessRepository;
		this.iBusinessMapper = iBusinessMapper;
	}

	@GetMapping("/restaurants/{restaurantId}/products")
	public List<ProductDTO> getProductsByRestaurant(@PathVariable Long restaurantId) {
		return productService.getProductsByRestaurant(restaurantId);
	}

	@GetMapping("/restaurants/slug/{slug}/products")
	public List<ProductDTO> getProductsByRestaurantSlug(@PathVariable String slug) {

		Business restaurant = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new RuntimeException("Restaurant not found"));

		return productService.getProductsByRestaurant(restaurant.getId());
	}

	@GetMapping("/restaurants/slug/{slug}")
	public BusinessDTO getRestaurantBySlug(@PathVariable String slug) {

		Business restaurant = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new RuntimeException("Restaurant not found"));

		return iBusinessMapper.toDTO(restaurant);
	}

	@PostMapping("/productos")
	public ProductDTO crear(@RequestBody ProductCreateDTO dto) {
		return productService.crearProducto(dto);
	}

	@PutMapping("/{id}")
	public ProductDTO actualizar(@PathVariable Long id, @RequestBody ProductDTO dto) {
		return productService.actualizarProducto(id, dto);
	}

}
