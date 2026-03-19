package com.carrito.saas.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderItemDTO;
import com.carrito.saas.dto.OrderKitchenDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.repository.enums.OrderType;
import com.carrito.saas.repository.jpa.BusinessRepository;
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
	private final IOrderKitchenMapper iOrderKitchenMapper;
	private final IOrderMapper iOrderMapper;
	private final SecurityService securityService;

	public OrderServiceImpl(OrderRepository orderRepository, BusinessRepository businessRepository,
			ProductRepository productRepository, IOrderKitchenMapper iOrderKitchenMapper, IOrderMapper iOrderMapper,
			SecurityService securityService) {
		super();
		this.orderRepository = orderRepository;
		this.businessRepository = businessRepository;
		this.productRepository = productRepository;
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

		// -------- agrupar productos duplicados --------

		Map<Long, Integer> groupedItems = new HashMap<>();

		for (OrderItemDTO item : request.getItems()) {

			if (item.getProductId() == null) {
				throw new RuntimeException("ProductId es obligatorio");
			}

			if (item.getQuantity() == null || item.getQuantity() <= 0) {
				throw new RuntimeException("Cantidad inválida");
			}

			groupedItems.merge(item.getProductId(), item.getQuantity(), Integer::sum);
		}

		List<Long> productIds = new ArrayList<>(groupedItems.keySet());

		// -------- 1 sola query --------

		List<Product> products = productRepository.findAllByIdInForUpdate(productIds);

		if (products.size() != productIds.size()) {
			throw new RuntimeException("Uno o más productos no existen");
		}

		Map<Long, Product> productMap = products.stream().collect(Collectors.toMap(Product::getId, p -> p));

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

		for (Map.Entry<Long, Integer> entry : groupedItems.entrySet()) {

			Product product = productMap.get(entry.getKey());

			Integer quantity = entry.getValue();

			if (quantity > 50) {
				throw new RuntimeException("Cantidad demasiado grande para el producto: " + entry.getKey());
			}

			if (!product.getBusinessId().equals(business.getId())) {
				throw new RuntimeException("Producto no pertenece al restaurante");
			}

			if (!product.isActive()) {
				throw new RuntimeException("Producto no disponible: " + product.getName());
			}

			if (product.getStock() != null && product.getStock() == 0) {
				throw new RuntimeException("Producto sin stock");
			}

			// Stock atomico.
			int updatedRows = productRepository.decrementStock(product.getId(), quantity);

			if (updatedRows == 0) {
				throw new RuntimeException("Stock insuficiente para el producto: " + product.getName());
			}

			OrderItem item = new OrderItem();

			item.setOrder(order);
			item.setProductId(product.getId());
			item.setProductName(product.getName());
			item.setPrice(product.getPrice());
			item.setQuantity(quantity);

			BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(quantity));

			item.setSubtotal(subtotal);

			items.add(item);

			total = total.add(subtotal);
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
		case CANCELLED:
			order.setCompletedAt(now);
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

}
