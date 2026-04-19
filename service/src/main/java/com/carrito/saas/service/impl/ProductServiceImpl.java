package com.carrito.saas.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.carrito.saas.service.interfaces.IProductService;
import com.carrito.saas.service.interfaces.IProductTrendService;
import com.carrito.saas.service.mapper.interfaces.IProductMapper;

@Service
public class ProductServiceImpl implements IProductService {

	private final ProductRepository productRepository;
	private final CategoryRepository categoryRepository;
	private final IProductMapper iProductMapper;
	private final IProductTrendService productTrendService;
	private final IProductPredictionService productPredictionService;
	private final ProductInsightService productInsightService;

	public ProductServiceImpl(ProductRepository productRepository, CategoryRepository categoryRepository,
			IProductMapper iProductMapper, IProductTrendService productTrendService,
			IProductPredictionService productPredictionService, ProductInsightService productInsightService) {

		this.productRepository = productRepository;
		this.categoryRepository = categoryRepository;
		this.iProductMapper = iProductMapper;
		this.productTrendService = productTrendService;
		this.productPredictionService = productPredictionService;
		this.productInsightService = productInsightService;
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

		// 5) Obtener trends en BULK (1 sola query)
		Map<Long, ProductTrendDTO> trends = productTrendService.getTrends(restaurantId, productIds);

		// Si querés activar insights en el futuro
		Map<Long, ProductPredictionDTO> predictions = productPredictionService.getPredictions(restaurantId, productIds);

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

	private PageResponse<ProductDTO> buildPageResponse(Page<Product> page, List<ProductDTO> content) {
		return PageResponse.<ProductDTO>builder().content(content).page(page.getNumber()).size(page.getSize())
				.totalElements(page.getTotalElements()).totalPages(page.getTotalPages()).last(page.isLast()).build();
	}

	@Override
	@Transactional
	public ProductDTO crearProducto(ProductCreateDTO dto) {

		validarProducto(dto.getName(), dto.getDescription(), dto.getPrice(), dto.getCost(), dto.getCategoryId());

		Category categoria = categoryRepository.findById(dto.getCategoryId())
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
	public ProductDTO actualizarProducto(Long id, ProductDTO dto) {
		Product p = productRepository.findById(id).orElseThrow();
		p.setCategory(categoryRepository.findById(dto.getCategoryId()).orElseThrow());
		p.setName(dto.getName().toString());

		p.setDescription(dto.getDescription());
		p.setPrice(dto.getPrice());
		p.setCost(dto.getCost());
		p.setStock(dto.getStock());
		p.setActive(dto.getActive());
		p.setUnitMeasure(dto.getUnitMeasure());

		return iProductMapper.toDTO(productRepository.save(p));
	}

	@Override
	public void eliminarProducto(Long id) {
		// TODO Auto-generated method stub

	}

	private void validarProducto(String nombre, String descripcion, BigDecimal precio, BigDecimal cost,
			Long categoriaId) {
		if (nombre == null || nombre.isBlank())
			throw new BusinessException("El nombre es obligatorio", ErrorType.VALIDATION);

		if (descripcion == null || descripcion.isBlank())
			throw new BusinessException("Descripcion requerido", ErrorType.VALIDATION);

		if (precio == null || precio.compareTo(BigDecimal.ZERO) < 0)
			throw new BusinessException("Precio inválido", ErrorType.VALIDATION);

		if (cost == null || cost.compareTo(BigDecimal.ZERO) < 0)
			throw new BusinessException("Costo inválido", ErrorType.VALIDATION);

		if (categoriaId == null)
			throw new BusinessException("Categoria requerida", ErrorType.VALIDATION);
	}

}
