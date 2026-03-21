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

	public OrderServiceImpl(OrderRepository orderRepository, BusinessRepository businessRepository,
			ProductRepository productRepository, ComboRepository comboRepository,
			CancellationReasonRepository cancellationReasonRepository, IOrderKitchenMapper iOrderKitchenMapper,
			IOrderMapper iOrderMapper, SecurityService securityService) {
		this.orderRepository = orderRepository;
		this.businessRepository = businessRepository;
		this.productRepository = productRepository;
		this.comboRepository = comboRepository;
		this.cancellationReasonRepository = cancellationReasonRepository;
		this.iOrderKitchenMapper = iOrderKitchenMapper;
		this.iOrderMapper = iOrderMapper;
		this.securityService = securityService;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public OrderDTO createOrder(String slug, OrderRequestDTO request) {

		if (request == null) {
			throw new RuntimeException("Pedido inválido");
		}

		if (slug == null || slug.isBlank()) {
			throw new RuntimeException("Restaurante es obligatorio");
		}

		if (request.getCustomerName() == null || request.getCustomerName().isBlank()) {
			throw new RuntimeException("El nombre del cliente es obligatorio");
		}

		if (request.getOrderType() == null) {
			throw new RuntimeException("Debe indicar tipo de pedido");
		}

		if (request.getPaymentMethod() == null) {
			throw new RuntimeException("Debe indicar método de pago");
		}

		if (request.getOrderType() == OrderType.DELIVERY
				&& (request.getCustomerAddress() == null || request.getCustomerAddress().isBlank())) {
			throw new RuntimeException("Debe indicar dirección para delivery");
		}

		if (request.getItems() == null || request.getItems().isEmpty()) {
			throw new RuntimeException("El pedido debe contener al menos un producto");
		}

		Business business = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new RuntimeException("No se encontró restaurante"));

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
				throw new RuntimeException("Cantidad inválida");
			}

			if (item.getProductId() != null) {
				productIds.add(item.getProductId());
			}

			if (item.getComboId() != null) {
				comboIds.add(item.getComboId());
			}
		}

		// traer productos
		List<Product> products = productRepository.findAllByIdInForUpdate(productIds);
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
					throw new RuntimeException("Producto no existe");
				}

				int updatedRows = productRepository.decrementStock(product.getId(), quantity);

				if (updatedRows == 0) {
					throw new RuntimeException("Stock insuficiente: " + product.getName());
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
					throw new RuntimeException("Combo no existe");
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

					int updatedRows = productRepository.decrementStock(product.getId(), finalQty);

					if (updatedRows == 0) {
						throw new RuntimeException("Stock insuficiente en combo: " + product.getName());
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
				.orElseThrow(() -> new RuntimeException("Pedido no encontrado o no autorizado"));

		OrderStatus currentStatus = order.getStatus();

		// Validar transición
		currentStatus.validateTransition(status);

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
				.orElseThrow(() -> new RuntimeException("Pedido no encontrado"));

		order.getStatus().validateTransition(OrderStatus.CANCELLED);

		// devolver stock si corresponde
		if (order.getStatus() == OrderStatus.NEW) {

			for (OrderItem item : order.getItems()) {

				 // ignorar root de combos
				if (Boolean.TRUE.equals(item.getComboRoot()))
					continue;

				int updated = productRepository.incrementStock(item.getProductId(), item.getQuantity());

				if (updated == 0) {
					throw new RuntimeException("No se pudo devolver stock del producto: " + item.getProductId());
				}
			}
		}

		CancellationReason reason = cancellationReasonRepository.findById(reasonId)
				.orElseThrow(() -> new RuntimeException("Motivo inválido"));

		order.setStatus(OrderStatus.CANCELLED);
		order.setCancellationReason(reason);
		order.setCancellationNote(note);
		order.setCancelledAt(LocalDateTime.now());

		Order saved = orderRepository.save(order);

		return iOrderMapper.toDTO(saved);
	}

}
