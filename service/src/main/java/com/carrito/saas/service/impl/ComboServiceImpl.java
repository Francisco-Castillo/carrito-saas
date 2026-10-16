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

		// Misma validación de forma que actualizar (una sola copia, ver
		// validarForma): nombre obligatorio y precio > 0 son 400
		// ANTES de cualquier lookup o mutación.
		validarForma(combo);

		if (combo.getCategoryId() == null)
			throw new BusinessException("La categoría es obligatoria", ErrorType.VALIDATION);

		if (combo.getProducts() == null || combo.getProducts().isEmpty())
			throw new BusinessException("El combo requiere al menos un producto", ErrorType.VALIDATION);

		// Categoría acotada al negocio del JWT: un id ajeno o inexistente
		// resuelve exactamente igual — 404, nunca un éxito silencioso.
		Category category = categoryRepository.findByIdAndBusinessId(combo.getCategoryId(), businessId)
				.orElseThrow(() -> new BusinessException(
						"Categoria no encontrada: " + combo.getCategoryId(), ErrorType.NOT_FOUND));

		// Duplicado dentro del MISMO negocio: finder acotado al tenant, misma
		// forma que CategoryServiceImpl.crearCategoria. El mismo nombre en
		// otro negocio es otro tenant y se permite.
		if (comboRepository.existsByNameIgnoreCaseAndBusiness(combo.getName().trim(), businessId)) {
			throw new BusinessException(
					"Ya existe un combo con ese nombre para este negocio", ErrorType.CONFLICT);
		}

		Combo nuevoCombo = new Combo();
		nuevoCombo.setName(combo.getName().trim());
		nuevoCombo.setPrice(combo.getPrice());
		nuevoCombo.setCategory(category);
		nuevoCombo.setActive(true);

		// Productos acotados al negocio del JWT: mismo finder que actualiza
		// (ver ownProductsById). Un id ajeno resuelve 404 nombrándolo.
		Map<Long, Product> ownProductsById = ownProductsById(combo.getProducts(), businessId);

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

	/**
	 * Validación de forma compartida por crear y actualizar (una sola copia):
	 * nombre obligatorio (trim después) y precio estrictamente mayor que
	 * cero — null, 0 y negativo son 400 con el mismo mensaje y el mismo
	 * código en las dos rutas. Un combo nunca es gratuito: es una regla de
	 * validación, no una decisión de producto.
	 */
	private void validarForma(ComboCreateDTO combo) {
		if (combo.getName() == null || combo.getName().isBlank())
			throw new BusinessException("El nombre del combo es obligatorio", ErrorType.VALIDATION);

		if (combo.getPrice() == null || combo.getPrice().signum() <= 0)
			throw new BusinessException("El precio del combo debe ser mayor que cero", ErrorType.VALIDATION);
	}

	/**
	 * Resuelve los productos del payload con un finder acotado al negocio del
	 * JWT: los ids viajan por un namespace global, así que un id ajeno no
	 * puede colarse con un findById desnudo. Compartido por crear y actualizar
	 * para que la misma garantía valga en las dos rutas.
	 */
	private Map<Long, Product> ownProductsById(List<ComboProductDTO> requested, Long businessId) {

		Set<Long> requestedIds = requested.stream()
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

		return ownProductsById;
	}

	@Override
	@Transactional
	public ComboDTO actualizarCombo(Long id, ComboCreateDTO combo) {

		Long businessId = securityService.getCurrentBusinessId();

		// Validación de forma ANTES de cualquier lookup o mutación: un payload
		// rechazado no puede aplicar ni parcialmente (y un NPE o una violación
		// de constraint serían 500, no 400). Misma copia que la creación — ver
		// validarForma.
		validarForma(combo);

		if (combo.getCategoryId() == null)
			throw new BusinessException("La categoría es obligatoria", ErrorType.VALIDATION);

		if (combo.getProducts() == null || combo.getProducts().isEmpty())
			throw new BusinessException("El combo requiere al menos un producto", ErrorType.VALIDATION);

		// El tenant ancla en el JWT: un id de combo ajeno al negocio resuelve
		// como 404, nunca como un éxito silencioso, y la fila ajena queda
		// exactamente igual.
		Combo comboExistente = comboRepository.findByIdAndBusinessId(id, businessId)
				.orElseThrow(() -> new BusinessException(
						"Combo no encontrado: " + id, ErrorType.NOT_FOUND));

		// Categoría acotada al negocio del JWT: un id ajeno o inexistente
		// resuelve exactamente igual — 404, nunca un éxito silencioso.
		Category category = categoryRepository.findByIdAndBusinessId(combo.getCategoryId(), businessId)
				.orElseThrow(() -> new BusinessException(
						"Categoria no encontrada: " + combo.getCategoryId(), ErrorType.NOT_FOUND));

		Map<Long, Product> ownProductsById = ownProductsById(combo.getProducts(), businessId);

		comboExistente.setName(combo.getName().trim());
		comboExistente.setPrice(combo.getPrice());
		comboExistente.setCategory(category);

		// La lista de items se REEMPLAZA: cascade = ALL + orphanRemoval = true
		// sobre Combo.items persiste el alta de los nuevos y la baja de los
		// viejos dentro de la misma transacción.
		comboExistente.getItems().clear();
		for (ComboProductDTO dto : combo.getProducts()) {
			ComboProduct cp = new ComboProduct();
			cp.setCombo(comboExistente);
			cp.setProduct(ownProductsById.get(dto.getProductId()));
			cp.setQuantity(BigDecimal.valueOf(dto.getQuantity()));
			comboExistente.getItems().add(cp);
		}

		return iComboMapper.toDTO(comboRepository.save(comboExistente));
	}

	@Override
	@Transactional
	public ComboDTO cambiarEstadoActivo(Long id, boolean activo) {

		Long businessId = securityService.getCurrentBusinessId();

		// El tenant ancla en el JWT: un id de combo ajeno al negocio resuelve
		// como 404, nunca como un éxito silencioso. Mismo finder que actualizar.
		Combo combo = comboRepository.findByIdAndBusinessId(id, businessId)
				.orElseThrow(() -> new BusinessException(
						"Combo no encontrado: " + id, ErrorType.NOT_FOUND));

		// Sólo el flag cambia: los items del combo no se tocan.
		combo.setActive(activo);

		return iComboMapper.toDTO(comboRepository.save(combo));
	}

	@Override
	@Transactional
	public void eliminarCombo(Long id) {

		Long businessId = securityService.getCurrentBusinessId();

		// El tenant ancla en el JWT: un id de combo ajeno al negocio resuelve
		// como 404, nunca como un éxito silencioso, y la fila ajena queda
		// exactamente igual.
		Combo combo = comboRepository.findByIdAndBusinessId(id, businessId)
				.orElseThrow(() -> new BusinessException(
						"Combo no encontrado: " + id, ErrorType.NOT_FOUND));

		// Misma forma que eliminarProducto: si algo referencia la fila, la FK
		// no permitiría el borrado — fallamos de forma explícita (409) antes
		// de chocar con la constraint y terminar en un 500 genérico.
		// order_items.combo_id lleva una FK real (a diferencia de
		// order_items.product_id, que no la tiene).
		if (comboRepository.existsOrderReferencing(combo.getId())) {
			throw new BusinessException(
					"El combo está siendo usado por pedidos y no puede eliminarse",
					ErrorType.CONFLICT);
		}

		// Los items (combo_products) se van con el combo vía cascade ALL +
		// orphanRemoval sobre Combo.items.
		comboRepository.delete(combo);
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
