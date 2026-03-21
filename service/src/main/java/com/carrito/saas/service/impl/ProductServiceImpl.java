package com.carrito.saas.service.impl;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.carrito.saas.dto.ProductCreateDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.service.interfaces.IProductService;
import com.carrito.saas.service.mapper.interfaces.IProductMapper;

@Service
public class ProductServiceImpl implements IProductService {

	private final ProductRepository productRepository;
	private final CategoryRepository categoryRepository;
	private final IProductMapper iProductMapper;

	public ProductServiceImpl(ProductRepository productRepository, CategoryRepository categoryRepository,
			IProductMapper iProductMapper) {
		super();
		this.productRepository = productRepository;
		this.categoryRepository = categoryRepository;
		this.iProductMapper = iProductMapper;
	}

	@Override
	public List<ProductDTO> getProductsByRestaurant(Long restaurantId) {
		// Buscamos los productos que pertenezcan al negocio, que esten activos y tengan
		// stock mayor a cero.
		List<Product> products = productRepository
				.findByCategory_Business_IdAndActiveTrueAndStockGreaterThan(restaurantId, 0);
		return iProductMapper.toListDTO(products);
	}

	@Override
	@Transactional
	public ProductDTO crearProducto(ProductCreateDTO dto) {
		validarProducto(dto.getName(), dto.getDescription(), dto.getPrice(), dto.getCost(), dto.getCategoryId());

		Category categoria = categoryRepository.findById(dto.getCategoryId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoria no encontrada"));

		// Normalizar el nombre del producto.
		String normalizedName = dto.getName().trim();

		// Validamos existencia
		boolean exists = productRepository.existsByNameAndCategory(normalizedName, categoria.getId());

		if (exists) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Ya existe un producto con ese nombre en esta categoría");
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
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre requerido");

		if (descripcion == null || descripcion.isBlank())
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Descripcion requerido");

		if (precio == null || precio.compareTo(BigDecimal.ZERO) < 0)
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Precio inválido");

		if (cost == null || cost.compareTo(BigDecimal.ZERO) < 0)
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Costo inválido");

		if (categoriaId == null)
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoria requerida");
	}

}
