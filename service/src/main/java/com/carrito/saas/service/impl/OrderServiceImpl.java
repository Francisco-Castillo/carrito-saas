package com.carrito.saas.service.impl;

import java.math.BigDecimal;
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
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.CancellationReason;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.ComboProduct;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.repository.enums.OrderType;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CancellationReasonRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.security.SecurityService;
import com.carrito.saas.service.interfaces.IOrderService;
import com.carrito.saas.service.mapper.interfaces.IOrderKitchenMapper;
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
	private final IOrderMapper iOrderMapper;
	private final SecurityService securityService;
	private final SingleBusinessResolver singleBusinessResolver;

	public OrderServiceImpl(OrderRepository orderRepository, BusinessRepository businessRepository,
			ProductRepository productRepository, ComboRepository comboRepository,
			CancellationReasonRepository cancellationReasonRepository, IOrderKitchenMapper iOrderKitchenMapper,
			IOrderMapper iOrderMapper, SecurityService securityService,
			SingleBusinessResolver singleBusinessResolver) {
		this.orderRepository = orderRepository;
		this.businessRepository = businessRepository;
		this.productRepository = productRepository;
		this.comboRepository = comboRepository;
		this.cancellationReasonRepository = cancellationReasonRepository;
		this.iOrderKitchenMapper = iOrderKitchenMapper;
		this.iOrderMapper = iOrderMapper;
		this.securityService = securityService;
		this.singleBusinessResolver = singleBusinessResolver;
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
		if (request.getCustomerName() == null || request.getCustomerName().isBlank()) {
			throw new BusinessException("El nombre del cliente es obligatorio", ErrorType.VALIDATION);
		}

		if (request.getOrderType() == null) {
			throw new BusinessException("Debe indicar tipo de pedido", ErrorType.VALIDATION);
		}

		if (request.getPaymentMethod() == null) {
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

		Order order = new Order();
		order.setStatus(OrderStatus.NEW);
		order.setBusiness(business);
		order.setCustomerName(request.getCustomerName());
		order.setCustomerPhone(request.getCustomerPhone());
		order.setOrderType(request.getOrderType());
		order.setCustomerAddress(request.getCustomerAddress());
		order.setPaymentMethod(request.getPaymentMethod());
		order.setNotes(request.getNotes());

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

				OrderItem item = new OrderItem();
				item.setOrder(order);
				item.setProductId(product.getId());
				item.setProductName(product.getName());
				item.setPrice(product.getPrice());
				item.setCost(product.getCost());
				item.setQuantity(quantity);
				item.setComboRoot(false);

				BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(quantity));
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

}
