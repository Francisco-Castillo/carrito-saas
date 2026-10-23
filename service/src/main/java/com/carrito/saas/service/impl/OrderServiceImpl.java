package com.carrito.saas.service.impl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderItemDTO;
import com.carrito.saas.dto.OrderKitchenDTO;
import com.carrito.saas.dto.OrderManagementDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.dto.SelectedOptionDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.CancellationReason;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.ComboProduct;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.entity.ProductOption;
import com.carrito.saas.repository.entity.ProductOptionGroup;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.repository.enums.OrderType;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CancellationReasonRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.TableRepository;
import com.carrito.saas.security.SecurityService;
import com.carrito.saas.service.interfaces.IBusinessOpenService;
import com.carrito.saas.service.interfaces.IOrderService;
import com.carrito.saas.service.mapper.interfaces.IOrderKitchenMapper;
import com.carrito.saas.service.mapper.interfaces.IOrderManagementMapper;
import com.carrito.saas.service.mapper.interfaces.IOrderMapper;

import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderServiceImpl implements IOrderService {

	private final OrderRepository orderRepository;
	private final BusinessRepository businessRepository;
	private final ProductRepository productRepository;
	private final ComboRepository comboRepository;
	private final CancellationReasonRepository cancellationReasonRepository;
	private final IOrderKitchenMapper iOrderKitchenMapper;
	private final IOrderManagementMapper iOrderManagementMapper;
	private final IOrderMapper iOrderMapper;
	private final SecurityService securityService;
	private final SingleBusinessResolver singleBusinessResolver;
	private final TableRepository tableRepository;
	private final IBusinessOpenService businessOpenService;
	private final Clock clock;

	public OrderServiceImpl(OrderRepository orderRepository, BusinessRepository businessRepository,
			ProductRepository productRepository, ComboRepository comboRepository,
			CancellationReasonRepository cancellationReasonRepository, IOrderKitchenMapper iOrderKitchenMapper,
			IOrderManagementMapper iOrderManagementMapper, IOrderMapper iOrderMapper, SecurityService securityService,
			SingleBusinessResolver singleBusinessResolver, TableRepository tableRepository,
			IBusinessOpenService businessOpenService, Clock clock) {
		this.orderRepository = orderRepository;
		this.businessRepository = businessRepository;
		this.productRepository = productRepository;
		this.comboRepository = comboRepository;
		this.cancellationReasonRepository = cancellationReasonRepository;
		this.iOrderKitchenMapper = iOrderKitchenMapper;
		this.iOrderManagementMapper = iOrderManagementMapper;
		this.iOrderMapper = iOrderMapper;
		this.securityService = securityService;
		this.singleBusinessResolver = singleBusinessResolver;
		this.tableRepository = tableRepository;
		this.businessOpenService = businessOpenService;
		this.clock = clock;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public OrderDTO createOrder(String slug, OrderRequestDTO request) {

		if (request == null) {
			throw new BusinessException("Pedido inválido", ErrorType.VALIDATION);
		}

		// Slugless mode (single-business deployment): the public QR URL no longer
		// carries ?restaurant=<slug>, so a null/blank slug resolves the deployment's
		// only business (fail-closed when there is not exactly one). A present slug
		// keeps the legacy path untouched.
		// SALON no exige datos del cliente: la mesa identifica al comensal (el
		// KDS y la consola muestran el nombre de la mesa). RETIRO/DELIVERY siguen
		// necesitando el nombre para llamar/entregar.
		boolean salon = request.getOrderType() == OrderType.SALON;
		if (!salon && (request.getCustomerName() == null || request.getCustomerName().isBlank())) {
			throw new BusinessException("El nombre del cliente es obligatorio", ErrorType.VALIDATION);
		}

		if (request.getOrderType() == null) {
			throw new BusinessException("Debe indicar tipo de pedido", ErrorType.VALIDATION);
		}

		// S4 de odd/tasks/pedido-qr-mesa.md: un pedido de salón se paga en la
		// mesa/caja — el cliente no elige forma de pago y el campo viaja null.
		// Todo otro tipo de pedido mantiene la exigencia legacy.
		if (request.getOrderType() != OrderType.SALON && request.getPaymentMethod() == null) {
			throw new BusinessException("Debe indicar método de pago", ErrorType.VALIDATION);
		}

		if (request.getOrderType() == OrderType.DELIVERY
				&& (request.getCustomerAddress() == null || request.getCustomerAddress().isBlank())) {
			throw new BusinessException("Debe indicar dirección para delivery", ErrorType.VALIDATION);
		}

		if (request.getItems() == null || request.getItems().isEmpty()) {
			throw new BusinessException("El pedido debe contener al menos un producto", ErrorType.VALIDATION);
		}

		Business business = (slug == null || slug.isBlank())
				? singleBusinessResolver.requireSingle()
				: businessRepository.findBySlug(slug)
						.orElseThrow(() -> new BusinessException("No se encontró restaurante", ErrorType.NOT_FOUND));

		// Baja lógica (S5 de odd/tasks/plataforma-editar-baja.md): un local
		// dado de baja NO existe públicamente — 404, ANTES del check de
		// horarios (un local de baja no está "cerrado": no está). Sin este
		// orden el pedido caería en el 400 de "El local está cerrado".
		if (!Boolean.TRUE.equals(business.getActive())) {
			throw new BusinessException("No se encontró restaurante", ErrorType.NOT_FOUND);
		}

		// Local cerrado: el pedido público no entra fuera del horario efectivo
		// del negocio — mismas reglas que el badge de la portada (turnos
		// ordenados, cruce de medianoche, excepciones por fecha). Una unidad
		// propia: el badge es decoración, esta es la regla real.
		if (!businessOpenService.isOpen(business.getId(), LocalDateTime.now(clock))) {
			throw new BusinessException("El local está cerrado", ErrorType.VALIDATION);
		}

		Order order = new Order();
		order.setStatus(OrderStatus.NEW);
		order.setBusiness(business);
		// En salón un nombre/phone en blanco se guarda como null (anónimo), no
		// como cadena vacía.
		String customerName = request.getCustomerName();
		order.setCustomerName(customerName == null || customerName.isBlank() ? null : customerName.trim());
		order.setCustomerPhone(request.getCustomerPhone());
		order.setOrderType(request.getOrderType());
		order.setCustomerAddress(request.getCustomerAddress());
		order.setPaymentMethod(request.getPaymentMethod());
		order.setNotes(request.getNotes());

		// Mesa: solo SALON la lleva. SALON la exige y valida que pertenezca al
		// negocio resuelto (un id ajeno NO se resuelve: 404 sin filtrar
		// existencia). RETIRO/DELIVERY ignoran cualquier tableId del request.
		if (request.getOrderType() == OrderType.SALON) {

			if (request.getTableId() == null) {
				throw new BusinessException("Debe indicar una mesa para pedidos en salón", ErrorType.VALIDATION);
			}

			RestaurantTable table = tableRepository
					.findByIdAndBusinessId(request.getTableId(), business.getId())
					.orElseThrow(() -> new BusinessException("Mesa no encontrada", ErrorType.NOT_FOUND));

			order.setRestaurantTable(table);
		}

		List<OrderItem> items = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;

		// Separar productos y combos
		List<Long> productIds = new ArrayList<>();
		List<Long> comboIds = new ArrayList<>();

		for (OrderItemDTO item : request.getItems()) {

			if (item.getQuantity() == null || item.getQuantity() <= 0) {
				throw new BusinessException("Cantidad inválida", ErrorType.VALIDATION);
			}

			if (item.getProductId() != null) {
				productIds.add(item.getProductId());
			}

			if (item.getComboId() != null) {
				comboIds.add(item.getComboId());
			}
		}

		// traer productos (scopeados por negocio: un id ajeno no entra al mapa)
		List<Product> products = productRepository.findAllByIdInForUpdate(productIds, business.getId());
		Map<Long, Product> productMap = products.stream().collect(Collectors.toMap(Product::getId, p -> p));

		// traer combos con sus productos

		List<Combo> combos;

		if (comboIds.isEmpty()) {
			combos = Collections.emptyList();
		} else {
			combos = comboRepository.findFullMenuCombosByIds(comboIds, business.getId());
		}

		Map<Long, Combo> comboMap = combos.stream().collect(Collectors.toMap(Combo::getId, c -> c));

		// Procesar items
		for (OrderItemDTO dto : request.getItems()) {

			int quantity = dto.getQuantity();

			// =========================
			// PRODUCTO NORMAL
			// =========================
			if (dto.getProductId() != null) {

				Product product = productMap.get(dto.getProductId());

				if (product == null) {
					throw new BusinessException("Producto no existe", ErrorType.NOT_FOUND);
				}

				int updatedRows = productRepository.decrementStock(product.getId(), quantity, business.getId());

				if (updatedRows == 0) {
					throw new BusinessException("Stock insuficiente: " + product.getName(), ErrorType.CONFLICT);
				}

				// Opciones elegidas (S4): resolver ids contra los grupos del
				// producto, validar obligatorios/min/max y recalcular el precio
				// unitario con los recargos. Snapshot persistido en el ítem.
				List<SelectedOptionDTO> seleccionadas = resolverOpciones(product, dto.getSelectedOptionIds());
				BigDecimal precioUnitario = product.getPrice();
				for (SelectedOptionDTO opt : seleccionadas) {
					precioUnitario = precioUnitario.add(opt.getPriceDelta());
				}

				OrderItem item = new OrderItem();
				item.setOrder(order);
				item.setProductId(product.getId());
				item.setProductName(product.getName());
				item.setPrice(precioUnitario);
				item.setCost(product.getCost());
				item.setQuantity(quantity);
				item.setComboRoot(false);
				item.setItemNotes(notasItem(dto.getNotes()));
				item.setSelectedOptions(seleccionadas.isEmpty() ? null : escribirJson(seleccionadas));

				BigDecimal subtotal = precioUnitario.multiply(BigDecimal.valueOf(quantity));
				item.setSubtotal(subtotal);

				items.add(item);
				total = total.add(subtotal);
			}

			// =========================
			// COMBO
			// =========================
			if (dto.getComboId() != null) {

				Combo combo = comboMap.get(dto.getComboId());

				if (combo == null) {
					throw new BusinessException("Combo no existe", ErrorType.NOT_FOUND);
				}

				// item root (para analytics)
				OrderItem root = new OrderItem();
				root.setOrder(order);
				root.setCombo(combo);
				root.setProductName(combo.getName());
				root.setPrice(combo.getPrice());
				root.setCost(BigDecimal.ZERO);
				root.setQuantity(quantity);
				root.setComboRoot(true);
				// La preferencia del combo vive en el root (el ticket de cocina
				// lo muestra junto al combo; los hijos son su descomposición).
				root.setItemNotes(notasItem(dto.getNotes()));

				BigDecimal comboSubtotal = combo.getPrice().multiply(BigDecimal.valueOf(quantity));
				root.setSubtotal(comboSubtotal);

				items.add(root);
				total = total.add(comboSubtotal);

				// Descomponer combo
				for (ComboProduct cp : combo.getItems()) {

					Product product = cp.getProduct();

					int finalQty = cp.getQuantity().multiply(BigDecimal.valueOf(quantity)).intValue();

					int updatedRows = productRepository.decrementStock(product.getId(), finalQty, business.getId());

					if (updatedRows == 0) {
						throw new BusinessException("Stock insuficiente en combo: " + product.getName(), ErrorType.CONFLICT);
					}

					OrderItem item = new OrderItem();
					item.setOrder(order);
					item.setProductId(product.getId());
					item.setProductName(product.getName());
					item.setPrice(product.getPrice());
					item.setQuantity(finalQty);
					item.setCombo(combo);
					item.setComboRoot(false);

					BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(finalQty));
					item.setSubtotal(subtotal);

					items.add(item);
				}
			}
		}

		order.setItems(items);
		order.setTotal(total);

		Integer lastOrderNumber = orderRepository.findMaxOrderNumberByBusiness(business.getId());
		order.setOrderNumber(lastOrderNumber + 1);

		Order savedOrder = orderRepository.save(order);

		return iOrderMapper.toDTO(savedOrder);
	}

	@Override
	public List<OrderKitchenDTO> getActiveOrders(Long businessId) {
		List<Order> orders = orderRepository.findActiveOrders(businessId);
		return iOrderKitchenMapper.toListDTO(orders);
	}

	@Override
	public OrderDTO updateStatus(Long orderId, OrderStatus status) {

		Long businessId = securityService.getCurrentBusinessId();

		Order order = orderRepository.findByIdAndBusinessId(orderId, businessId)
				.orElseThrow(() -> new BusinessException("Pedido no encontrado o no autorizado", ErrorType.NOT_FOUND));

		OrderStatus currentStatus = order.getStatus();

		// Validar transición: un salto de estado inválido (board desactualizado,
		// doble click, llamada directa) es un CONFLICTO de estado — 409 — no un
		// error del servidor. El enum no lanza: la decisión de negocio es del
		// servicio.
		if (!currentStatus.canTransitionTo(status)) {
			throw new BusinessException(
					"Transición inválida: " + currentStatus + " -> " + status,
					ErrorType.CONFLICT);
		}

		// Actualizar estado
		order.setStatus(status);

		LocalDateTime now = LocalDateTime.now();

		switch (status) {

		case PREPARING:
			order.setPreparingAt(now);
			break;

		case READY:
			order.setReadyAt(now);
			break;

		case DELIVERED:
	        order.setCompletedAt(now);
	        break;

	    case CANCELLED:
	        order.setCancelledAt(now);
	        break;

		default:
			break;
		}

		Order orderSaved = orderRepository.save(order);
		return iOrderMapper.toDTO(orderSaved);
	}

	@Override
	public List<OrderKitchenDTO> getActiveOrders() {
		Long businessId = securityService.getCurrentBusinessId();

		List<Order> orders = orderRepository.findActiveOrders(businessId);

		return iOrderKitchenMapper.toListDTO(orders);
	}

	@Override
	@Transactional(readOnly = true)
	public List<OrderManagementDTO> getOrdersByDay(LocalDate date) {

		Long businessId = securityService.getCurrentBusinessId();

		LocalDate day = date != null ? date : LocalDate.now();

		// Ventana [inicio del día, inicio del día siguiente): cubre el día
		// completo sin depender de DATE() sobre la columna.
		LocalDateTime start = day.atStartOfDay();
		LocalDateTime end = day.plusDays(1).atStartOfDay();

		List<Order> orders = orderRepository.findOrdersByDay(businessId, start, end);

		return iOrderManagementMapper.toListDTO(orders);
	}

	@Override
	@Transactional
	public OrderDTO cancelOrder(Long orderId, Long reasonId, String note) {

		Long businessId = securityService.getCurrentBusinessId();

		Order order = orderRepository.findByIdAndBusinessId(orderId, businessId)
				.orElseThrow(() -> new BusinessException("Pedido no encontrado", ErrorType.NOT_FOUND));

		if (!order.getStatus().canTransitionTo(OrderStatus.CANCELLED)) {
			throw new BusinessException(
					"Transición inválida: " + order.getStatus() + " -> CANCELLED",
					ErrorType.CONFLICT);
		}

		// devolver stock si corresponde
		if (order.getStatus() == OrderStatus.NEW) {

			for (OrderItem item : order.getItems()) {

				 // ignorar root de combos
				if (Boolean.TRUE.equals(item.getComboRoot()))
					continue;

				int updated = productRepository.incrementStock(item.getProductId(), item.getQuantity(), businessId);

				if (updated == 0) {
					throw new RuntimeException("No se pudo devolver stock del producto: " + item.getProductId());
				}
			}
		}

		CancellationReason reason = cancellationReasonRepository.findById(reasonId)
				.orElseThrow(() -> new BusinessException("Motivo inválido", ErrorType.VALIDATION));

		order.setStatus(OrderStatus.CANCELLED);
		order.setCancellationReason(reason);
		order.setCancellationNote(note);
		order.setCancelledAt(LocalDateTime.now());

		Order saved = orderRepository.save(order);

		return iOrderMapper.toDTO(saved);
	}

	/**
	 * Resuelve los ids de opciones elegidas contra los grupos del producto y
	 * valida las reglas del dueño (odd/tasks/product-options.md S4):
	 * - toda opción tiene que pertenecer al producto pedido (404, igual que
	 *   un producto ajeno);
	 * - grupo obligatorio: al menos 1 elección (no múltiple: exactamente 1);
	 * - grupo múltiple: cantidad entre minSelections y maxSelections.
	 * El orden del resultado sigue el orden de definición de los grupos
	 * (el mismo con el que el dueño los cargó).
	 */
	private List<SelectedOptionDTO> resolverOpciones(Product product, List<Long> opcionIds) {

		List<ProductOptionGroup> grupos = product.getOptionGroups();
		if (grupos == null || grupos.isEmpty()) {
			if (opcionIds != null && !opcionIds.isEmpty()) {
				throw new BusinessException("El producto " + product.getName() + " no tiene opciones",
						ErrorType.VALIDATION);
			}
			return Collections.emptyList();
		}

		List<Long> elegidos = opcionIds == null ? Collections.emptyList() : opcionIds;
		List<SelectedOptionDTO> resueltas = new ArrayList<>();
		int elegidosRestantes = elegidos.size();

		for (ProductOptionGroup grupo : grupos) {

			List<SelectedOptionDTO> delGrupo = new ArrayList<>();
			for (ProductOption opcion : grupo.getOptions()) {
				if (elegidos.contains(opcion.getId())) {
					SelectedOptionDTO dto = new SelectedOptionDTO();
					dto.setOptionId(opcion.getId());
					dto.setGroupName(grupo.getName());
					dto.setOptionName(opcion.getName());
					dto.setPriceDelta(opcion.getPriceDelta());
					delGrupo.add(dto);
				}
			}
			elegidosRestantes -= delGrupo.size();

			int cantidad = delGrupo.size();
			if (grupo.isRequired() && cantidad == 0) {
				throw new BusinessException("Tenés que elegir una opción para \"" + grupo.getName() + "\"",
						ErrorType.VALIDATION);
			}
			if (grupo.isMultiple()) {
				Integer min = grupo.getMinSelections();
				Integer max = grupo.getMaxSelections();
				if (min != null && cantidad < min) {
					throw new BusinessException("Elegí al menos " + min + " opción(es) para \"" + grupo.getName()
							+ "\"", ErrorType.VALIDATION);
				}
				if (max != null && cantidad > max) {
					throw new BusinessException("Podés elegir como máximo " + max + " opción(es) para \""
							+ grupo.getName() + "\"", ErrorType.VALIDATION);
				}
			} else if (cantidad > 1) {
				throw new BusinessException("En \"" + grupo.getName() + "\" podés elegir solo una opción",
						ErrorType.VALIDATION);
			}

			resueltas.addAll(delGrupo);
		}

		if (elegidosRestantes > 0) {
			// ids que no pertenecen a ningún grupo del producto
			throw new BusinessException("Opción inválida para el producto " + product.getName(), ErrorType.NOT_FOUND);
		}

		return resueltas;
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
			new com.fasterxml.jackson.databind.ObjectMapper()
					.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	/** Normaliza las preferencias del ítem: trim, blank → null, y un límite
	 * legible (400) para que un texto descontrolado no ensucie el ticket de
	 * cocina. Mismo estilo que las validaciones del pedido. */
	private String notasItem(String notas) {
		if (notas == null) {
			return null;
		}
		String recortada = notas.trim();
		if (recortada.isEmpty()) {
			return null;
		}
		if (recortada.length() > 300) {
			throw new BusinessException("Las preferencias del producto no pueden superar 300 caracteres",
					ErrorType.VALIDATION);
		}
		return recortada;
	}

	private String escribirJson(List<SelectedOptionDTO> seleccionadas) {
		try {
			return JSON.writeValueAsString(seleccionadas);
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException("No se pudo serializar las opciones elegidas", e);
		}
	}

	/**
	 * Lee el snapshot JSON de las opciones de un ítem (S5). Un JSON ilegible
	 * no tira abajo el pedido: lista vacía y log (el pedido existe, mostrar
	 * el resto es más importante que la selección).
	 */
	public static List<SelectedOptionDTO> leerOpcionesJson(String json) {
		if (json == null || json.isBlank())
			return Collections.emptyList();
		try {
			return JSON.readValue(json,
					JSON.getTypeFactory().constructCollectionType(List.class, SelectedOptionDTO.class));
		} catch (Exception e) {
			return Collections.emptyList();
		}
	}

}
