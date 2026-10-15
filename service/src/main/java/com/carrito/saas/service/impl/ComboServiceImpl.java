package com.carrito.saas.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.ComboCreateDTO;
import com.carrito.saas.dto.ComboDTO;
import com.carrito.saas.dto.ComboProductDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.ComboProduct;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.ComboProductRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.service.interfaces.IComboService;
import com.carrito.saas.service.mapper.interfaces.IComboMapper;
import com.carrito.saas.security.SecurityService;

@Service
public class ComboServiceImpl implements IComboService {

	private final ComboRepository comboRepository;
	private final ProductRepository productRepository;
	private final ComboProductRepository comboProductRepository;
	private final CategoryRepository categoryRepository;
	private final IComboMapper iComboMapper;
	private final SecurityService securityService;

	public ComboServiceImpl(ComboRepository comboRepository, ProductRepository productRepository,
			ComboProductRepository comboProductRepository, CategoryRepository categoryRepository,
			IComboMapper iComboMapper, SecurityService securityService) {
		this.comboRepository = comboRepository;
		this.productRepository = productRepository;
		this.comboProductRepository = comboProductRepository;
		this.categoryRepository = categoryRepository;
		this.iComboMapper = iComboMapper;
		this.securityService = securityService;
	}

	@Transactional
	@Override
	public ComboDTO crearCombo(ComboCreateDTO combo) {

		Long businessId = securityService.getCurrentBusinessId();

		if (combo.getCategoryId() == null)
			throw new BusinessException("La categoría es obligatoria", ErrorType.VALIDATION);

		if (combo.getProducts() == null || combo.getProducts().isEmpty())
			throw new BusinessException("El combo requiere al menos un producto", ErrorType.VALIDATION);

		// Categoría acotada al negocio del JWT: un id ajeno o inexistente
		// resuelve exactamente igual — 404, nunca un éxito silencioso.
		Category category = categoryRepository.findByIdAndBusinessId(combo.getCategoryId(), businessId)
				.orElseThrow(() -> new BusinessException(
						"Categoria no encontrada: " + combo.getCategoryId(), ErrorType.NOT_FOUND));

		Combo nuevoCombo = new Combo();
		nuevoCombo.setName(combo.getName());
		nuevoCombo.setPrice(combo.getPrice());
		nuevoCombo.setCategory(category);
		nuevoCombo.setActive(true);

		// Productos acotados al negocio del JWT: los ids viajan por un
		// namespace global, así que un id ajeno no puede colarse con un
		// findById desnudo.
		Set<Long> requestedIds = combo.getProducts().stream()
				.map(ComboProductDTO::getProductId)
				.collect(Collectors.toSet());

		Map<Long, Product> ownProductsById = productRepository
				.findAllByIdInAndCategory_Business_Id(requestedIds, businessId).stream()
				.collect(Collectors.toMap(Product::getId, Function.identity()));

		if (ownProductsById.size() < requestedIds.size()) {
			Long offendingId = requestedIds.stream()
					.filter(id -> !ownProductsById.containsKey(id))
					.findFirst()
					.orElseThrow();
			throw new BusinessException(
					"Producto no encontrado para este negocio: " + offendingId, ErrorType.NOT_FOUND);
		}

		Combo comboSaved = comboRepository.save(nuevoCombo);

		List<ComboProduct> productos = combo.getProducts().stream().map(dto -> {
			ComboProduct cp = new ComboProduct();
			cp.setCombo(comboSaved);
			cp.setProduct(ownProductsById.get(dto.getProductId()));
			cp.setQuantity(BigDecimal.valueOf(dto.getQuantity()));
			return comboProductRepository.save(cp);
		}).toList();

		comboSaved.setItems(productos);

		return iComboMapper.toDTO(comboSaved);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ComboDTO> findAllByBusinessId() {

		Long businessId = securityService.getCurrentBusinessId();

		// findFullMenuCombos ya acota por cat.business.id y trae items y
		// productos en una sola consulta. El mapeo completo vive en
		// IComboMapperImpl.toListDTO, con la misma lógica que toDTO por item.
		return iComboMapper.toListDTO(comboRepository.findFullMenuCombos(businessId));
	}

}
