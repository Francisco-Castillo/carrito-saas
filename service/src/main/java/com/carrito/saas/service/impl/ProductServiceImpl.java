package com.carrito.saas.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.PageResponse;
import com.carrito.saas.dto.ProductCreateDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.dto.ProductFilterDTO;
import com.carrito.saas.dto.ProductInsight;
import com.carrito.saas.dto.ProductPredictionDTO;
import com.carrito.saas.dto.ProductTrendDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.service.interfaces.IProductPredictionService;
import com.carrito.saas.service.impl.ImageStorageService;
import com.carrito.saas.service.interfaces.IProductService;
import com.carrito.saas.service.interfaces.IProductTrendService;
import com.carrito.saas.service.mapper.interfaces.IProductMapper;
import com.carrito.saas.security.SecurityService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ProductServiceImpl implements IProductService {

	private final ProductRepository productRepository;
	private final CategoryRepository categoryRepository;
	private final IProductMapper iProductMapper;
	private final IProductTrendService productTrendService;
	private final IProductPredictionService productPredictionService;
	private final ProductInsightService productInsightService;
	private final SecurityService securityService;
	private final ImageStorageService imageStorageService;

	public ProductServiceImpl(ProductRepository productRepository, CategoryRepository categoryRepository,
			IProductMapper iProductMapper, IProductTrendService productTrendService,
			IProductPredictionService productPredictionService, ProductInsightService productInsightService,
			SecurityService securityService, ImageStorageService imageStorageService) {

		this.productRepository = productRepository;
		this.categoryRepository = categoryRepository;
		this.iProductMapper = iProductMapper;
		this.productTrendService = productTrendService;
		this.productPredictionService = productPredictionService;
		this.productInsightService = productInsightService;
		this.securityService = securityService;
		this.imageStorageService = imageStorageService;
	}

	@Override
	public PageResponse<ProductDTO> getProductsByRestaurant(Long restaurantId, int page, int size, String sortBy,
			String sortDir, ProductFilterDTO filter) {

		// 1) Sorting
		Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();

		Pageable pageable = PageRequest.of(page, size, sort);

		// 2) Specification
		Specification<Product> spec = ProductSpecification.byRestaurant(restaurantId)
				.and(ProductSpecification.isActive()).and(ProductSpecification.hasStock())
				.and(ProductSpecification.withFilters(filter));

		// 3) Query principal
		Page<Product> productPage = productRepository.findAll(spec, pageable);

		List<ProductDTO> content = iProductMapper.toListDTO(productPage.getContent());

		// IMPORTANTE: evitar trabajo innecesario
		if (content.isEmpty()) {
			return buildPageResponse(productPage, content);
		}

		// 4) Extraer IDs
		List<Long> productIds = new ArrayList<>(content.size());
		for (ProductDTO p : content) {
			productIds.add(p.getId());
		}

		// 5) Enriquecimiento analítico opcional: tendencias y predicciones
		// viven en tablas JDBC (product_trends, product_predictions) que no
		// tienen entidad ni migración, así que en una base sin el schema de
		// analítica la consulta falla. El catálogo es funcionalidad core y
		// las tendencias son enriquecimiento: se degrada (log de warning,
		// sin datos) en vez de tumbar toda la ruta con un 500.
		Map<Long, ProductTrendDTO> trends = enriquecimientoOpcional(
				"tendencias", () -> productTrendService.getTrends(restaurantId, productIds));

		Map<Long, ProductPredictionDTO> predictions = enriquecimientoOpcional(
				"predicciones", () -> productPredictionService.getPredictions(restaurantId, productIds));

		// Merge + Insight
		for (ProductDTO p : content) {

			ProductTrendDTO trend = trends.get(p.getId());

			if (trend != null) {
				p.setTrendPercentage(trend.getTrendPercentage());
				p.setTrendDirection(trend.getTrendDirection());
			} else {
				p.setTrendPercentage(0.0);
				p.setTrendDirection("STABLE");
			}

			ProductPredictionDTO prediction = predictions.get(p.getId());

			ProductInsight insight = productInsightService.calculate(trend != null ? trend.getTrendPercentage() : null,
					prediction != null ? prediction.getPredictedGrowth() : null);

			p.setProductInsight(insight);
		}

		return buildPageResponse(productPage, content);
	}

	/**
	 * Ejecuta una consulta de enriquecimiento analítico opcional. Un fallo de
	 * acceso a datos (tabla faltante, query rota, analítica lenta que explota)
	 * se registra como warning y se responde con un mapa vacío: el catálogo
	 * sale sin ese dato en vez de fallar completo.
	 */
	private <K, V> Map<K, V> enriquecimientoOpcional(String nombre, Supplier<Map<K, V>> consulta) {
		try {
			return consulta.get();
		} catch (DataAccessException e) {
			log.warn("Enriquecimiento de {} omitido: la consulta analítica falló ({})",
					nombre, e.getMessage());
			return Collections.emptyMap();
		}
	}

	private PageResponse<ProductDTO> buildPageResponse(Page<Product> page, List<ProductDTO> content) {
		return PageResponse.<ProductDTO>builder().content(content).page(page.getNumber()).size(page.getSize())
				.totalElements(page.getTotalElements()).totalPages(page.getTotalPages()).last(page.isLast()).build();
	}

	@Override
	@Transactional
	public ProductDTO crearProducto(ProductCreateDTO dto) {

		validarProducto(dto.getName(), dto.getDescription(), dto.getPrice(), dto.getCost(), dto.getStock(),
				dto.getCategoryId());

		// El tenant ancla en el JWT: crear dentro de una categoría ajena al
		// negocio resuelve como 404, nunca como un éxito silencioso.
		Category categoria = categoryRepository
				.findByIdAndBusinessId(dto.getCategoryId(), securityService.getCurrentBusinessId())
				.orElseThrow(() -> new BusinessException("Categoria no encontrada", ErrorType.NOT_FOUND));

		// Normalizar nombre
		String normalizedName = dto.getName().trim();

		// Validar duplicado
		boolean exists = productRepository.existsByNameAndCategory(normalizedName, categoria.getId());

		if (exists) {
			throw new BusinessException("Ya existe un producto con ese nombre en esta categoría", ErrorType.CONFLICT);
		}

		Product p = new Product();
		p.setName(normalizedName);
		p.setCategory(categoria);
		p.setDescription(dto.getDescription());
		p.setPrice(dto.getPrice());
		p.setCost(dto.getCost());
		p.setStock(dto.getStock());
		p.setActive(true);
		p.setUnitMeasure(dto.getUnitMeasure());

		return iProductMapper.toDTO(productRepository.save(p));
	}

	@Override
	@Transactional
	public ProductDTO actualizarProducto(Long id, ProductDTO dto) {

		// name/price/cost son NOT NULL: validarlos ANTES de tocar la entidad
		// convierte un NPE o una violación de constraint (ambos 500) en un 400
		// honesto que nombra el campo faltante.
		validarProductoRequerido(dto);
		// El tenant ancla en el JWT: un id de producto ajeno al negocio resuelve
		// como 404, nunca como un éxito silencioso, y la fila ajena queda
		// exactamente igual.
		Long businessId = securityService.getCurrentBusinessId();

		Product p = productRepository.findByIdAndCategory_Business_Id(id, businessId)
				.orElseThrow(() -> new BusinessException("Producto no encontrado", ErrorType.NOT_FOUND));

		// La categoría destino también debe ser propia: re-apuntar el producto
		// a la categoría de otro negocio resuelve como 404 y no toca la fila.
		Category categoria = categoryRepository.findByIdAndBusinessId(dto.getCategoryId(), businessId)
				.orElseThrow(() -> new BusinessException("Categoria no encontrada", ErrorType.NOT_FOUND));

		p.setCategory(categoria);
		p.setName(dto.getName().trim());

		p.setDescription(dto.getDescription());
		p.setPrice(dto.getPrice());
		p.setCost(dto.getCost());
		p.setStock(dto.getStock());
		p.setActive(dto.getActive());
		p.setUnitMeasure(dto.getUnitMeasure());

		return iProductMapper.toDTO(productRepository.save(p));
	}

	@Override
	@Transactional
	public void eliminarProducto(Long id) {

		Long businessId = securityService.getCurrentBusinessId();

		Product p = productRepository.findByIdAndCategory_Business_Id(id, businessId)
				.orElseThrow(() -> new BusinessException("Producto no encontrado", ErrorType.NOT_FOUND));

		// combo_products.producto_id lleva una FK real (RESTRICT): un producto
		// usado por un combo no puede borrarse. Fallamos de forma explícita en
			// vez de chocar con la FK y terminar en un 500 genérico.
		if (productRepository.existsInAnyCombo(p.getId())) {
			throw new BusinessException(
					"El producto está siendo usado por un combo y no puede eliminarse",
					ErrorType.CONFLICT);
		}

		productRepository.delete(p);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ProductDTO> findAllByBusinessId() {

		Long businessId = securityService.getCurrentBusinessId();

		// Panel de administración: activos E inactivos (el panel muestra una
		// columna de estado), siempre acotados al negocio del JWT.
		return iProductMapper.toListDTO(productRepository.findByCategory_Business_Id(businessId));
	}

	@Override
	@Transactional
	public ProductDTO cambiarEstadoActivo(Long id, boolean activo) {

		Long businessId = securityService.getCurrentBusinessId();

		Product p = productRepository.findByIdAndCategory_Business_Id(id, businessId)
				.orElseThrow(() -> new BusinessException("Producto no encontrado", ErrorType.NOT_FOUND));

		p.setActive(activo);

		return iProductMapper.toDTO(productRepository.save(p));
	}

	@Override
	@Transactional
	public ProductDTO actualizarImagen(Long id, byte[] data, String contentType) {

		Long businessId = securityService.getCurrentBusinessId();

		Product p = productRepository.findByIdAndCategory_Business_Id(id, businessId)
				.orElseThrow(() -> new BusinessException("Producto no encontrado", ErrorType.NOT_FOUND));

		// Reemplazo = guardar la nueva primero (falla en disco no toca la fila)
		// y recién después borrar la vieja si existía.
		String oldUrl = p.getImageUrl();
		String newUrl = imageStorageService.store(data, contentType);
		p.setImageUrl(newUrl);

		if (oldUrl != null && !oldUrl.equals(newUrl)) {
			imageStorageService.deleteByUrl(oldUrl);
		}

		return iProductMapper.toDTO(productRepository.save(p));
	}

	@Override
	@Transactional
	public ProductDTO eliminarImagen(Long id) {

		Long businessId = securityService.getCurrentBusinessId();

		Product p = productRepository.findByIdAndCategory_Business_Id(id, businessId)
				.orElseThrow(() -> new BusinessException("Producto no encontrado", ErrorType.NOT_FOUND));

		String url = p.getImageUrl();
		p.setImageUrl(null);
		imageStorageService.deleteByUrl(url);

		return iProductMapper.toDTO(productRepository.save(p));
	}

	private void validarProductoRequerido(ProductDTO dto) {
		if (dto.getName() == null || dto.getName().isBlank())
			throw new BusinessException("El nombre es obligatorio", ErrorType.VALIDATION);

		if (dto.getPrice() == null)
			throw new BusinessException("El precio es obligatorio", ErrorType.VALIDATION);

		// El precio PRESENTE pasa por la regla compartida (ver validarPrecio):
		// un producto nunca es gratuito, igual que un combo.
		validarPrecio(dto.getPrice());

		validarStock(dto.getStock());

		if (dto.getCost() == null)
			throw new BusinessException("El costo es obligatorio", ErrorType.VALIDATION);

		// El costo PRESENTE pasa por la regla compartida con crear (ver
		// validarProducto): cost no es price, así que 0 es legítimo (producto
		// sin costo registrado); solo el NEGATIVO es 400, con el mismo mensaje
		// en las dos rutas.
		if (dto.getCost().signum() < 0)
			throw new BusinessException("Costo inválido", ErrorType.VALIDATION);
	}

	private void validarStock(Integer stock) {
		if (stock != null && stock < 0)
			throw new BusinessException("Stock inválido", ErrorType.VALIDATION);
	}

	/**
	 * Regla del precio, una sola copia compartida por crear y actualizar
	 * (espejo de validarForma en ComboServiceImpl): un producto nunca es
	 * gratuito, así que un precio PRESENTE debe ser estrictamente mayor que
	 * cero — 0 y negativo son 400 con el mismo mensaje en las dos rutas. El
	 * null no se juzga acá: cada ruta ya lo rechaza antes con su contrato
	 * propio ("El precio es obligatorio"), y un precio ausente no es un
	 * precio de cero.
	 */
	private void validarPrecio(BigDecimal precio) {
		if (precio != null && precio.signum() <= 0)
			throw new BusinessException("El precio del producto debe ser mayor que cero", ErrorType.VALIDATION);
	}

	private void validarProducto(String nombre, String descripcion, BigDecimal precio, BigDecimal cost,
			Integer stock, Long categoriaId) {
		if (nombre == null || nombre.isBlank())
			throw new BusinessException("El nombre es obligatorio", ErrorType.VALIDATION);

		if (descripcion == null || descripcion.isBlank())
			throw new BusinessException("Descripcion requerido", ErrorType.VALIDATION);

		// El null es ausencia de dato (contrato "El precio es obligatorio",
		// mismo mensaje que actualizar); el valor PRESENTE pasa por la regla
		// compartida de validarPrecio.
		if (precio == null)
			throw new BusinessException("El precio es obligatorio", ErrorType.VALIDATION);

		validarPrecio(precio);

		if (cost == null || cost.compareTo(BigDecimal.ZERO) < 0)
			throw new BusinessException("Costo inválido", ErrorType.VALIDATION);

		validarStock(stock);

		if (categoriaId == null)
			throw new BusinessException("Categoria requerida", ErrorType.VALIDATION);
	}

}
