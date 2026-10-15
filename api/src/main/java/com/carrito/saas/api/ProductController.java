package com.carrito.saas.api;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.BusinessDTO;
import com.carrito.saas.dto.PageResponse;
import com.carrito.saas.dto.ProductCreateDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.dto.ProductFilterDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.security.ISecurityService;
import com.carrito.saas.service.interfaces.IProductService;
import com.carrito.saas.service.mapper.interfaces.IBusinessMapper;

import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api")
@Slf4j
public class ProductController {

	private final IProductService productService;
	private final BusinessRepository businessRepository;
	private final IBusinessMapper iBusinessMapper;
	private final ISecurityService securityService;

	public ProductController(IProductService productService, BusinessRepository businessRepository,
			IBusinessMapper iBusinessMapper, ISecurityService securityService) {
		super();
		this.productService = productService;
		this.businessRepository = businessRepository;
		this.iBusinessMapper = iBusinessMapper;
		this.securityService = securityService;
	}

	/**
	 * Catálogo acotado por id de restaurante: el negocio sale del JWT y solo
	 * se responde cuando el restaurantId del path ES el negocio del caller; un
	 * id ajeno o inexistente resuelve como 404 — nunca un 200 silencioso con
	 * cuerpo vacío (el stub anterior hacía {@code return null}). El listado
	 * sale de findAllByBusinessId, anclado al JWT igual que /api/products.
	 */
	@GetMapping("/restaurants/{restaurantId}/products")
	public List<ProductDTO> getProductsByRestaurant(@PathVariable Long restaurantId) {

		Long businessId = securityService.getCurrentBusinessId();

		if (!restaurantId.equals(businessId)) {
			throw new BusinessException(
					"Restaurante no encontrado: " + restaurantId, ErrorType.NOT_FOUND);
		}

		return productService.findAllByBusinessId();
	}

	@GetMapping("/restaurants/slug/{slug}/products")
	public ResponseEntity<PageResponse<ProductDTO>> getProductsByRestaurantSlug(
	        @PathVariable String slug,
	        @RequestParam(defaultValue = "0") int page,
	        @RequestParam(defaultValue = "10") int size,
	        @RequestParam(defaultValue = "name") String sortBy,
	        @RequestParam(defaultValue = "asc") String sortDir,
	        @RequestParam(required = false) String search,
	        @RequestParam(required = false) Long categoryId,
	        @RequestParam(required = false) BigDecimal minPrice,
	        @RequestParam(required = false) BigDecimal maxPrice
	) {

	    Business restaurant = businessRepository.findBySlug(slug)
	            .orElseThrow(() -> new RuntimeException("Restaurant not found"));

	    ProductFilterDTO filter = ProductFilterDTO.builder()
	            .search(search)
	            .categoryId(categoryId)
	            .minPrice(minPrice)
	            .maxPrice(maxPrice)
	            .build();

	    PageResponse<ProductDTO> response = productService
	            .getProductsByRestaurant(restaurant.getId(), page, size, sortBy, sortDir, filter);

	    return ResponseEntity.ok(response);
	}

	@GetMapping("/restaurants/slug/{slug}")
	public BusinessDTO getRestaurantBySlug(@PathVariable String slug) {

		Business restaurant = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new RuntimeException("Restaurant not found"));

		return iBusinessMapper.toDTO(restaurant);
	}

	@PostMapping("/productos")
	public ResponseEntity<ProductDTO> crear(@RequestBody ProductCreateDTO dto) {
		log.error("TEST ERROR LOG");
		ProductDTO creado = productService.crearProducto(dto);

		return ResponseEntity.ok(creado);
	}

	@PutMapping("/{id}")
	public ProductDTO actualizar(@PathVariable Long id, @RequestBody ProductDTO dto) {
		return productService.actualizarProducto(id, dto);
	}

	/**
	 * Panel de administración: TODOS los productos del negocio autenticado
	 * (activos e inactivos, el panel muestra una columna de estado). El
	 * businessId sale del JWT, nunca del request.
	 */
	@GetMapping("/products")
	public List<ProductDTO> listarProductosDelNegocio() {
		return productService.findAllByBusinessId();
	}

	/**
	 * Panel de administración: activa o desactiva un producto propio. Un id
	 * ajeno al negocio resuelve como 404, nunca como un éxito silencioso.
	 */
	@PutMapping("/products/{id}/activar")
	public ProductDTO activarProducto(@PathVariable Long id, @RequestParam boolean activo) {
		return productService.cambiarEstadoActivo(id, activo);
	}

	/**
	 * Panel de administración: elimina un producto propio. Un producto usado
	 * por un combo falla con 409 (FK RESTRICT en combo_products), un id ajeno
	 * resuelve como 404.
	 */
	@DeleteMapping("/products/{id}")
	public ResponseEntity<Void> eliminarProducto(@PathVariable Long id) {
		productService.eliminarProducto(id);
		return ResponseEntity.noContent().build();
	}

}
