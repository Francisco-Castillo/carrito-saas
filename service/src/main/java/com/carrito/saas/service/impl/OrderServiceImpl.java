package com.carrito.saas.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.OrderItemDTO;
import com.carrito.saas.dto.OrderKitchenDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.dto.OrderResponseDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.repository.enums.OrderType;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.service.interfaces.IOrderService;
import com.carrito.saas.service.mapper.interfaces.IOrderKitchenMapper;

import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderServiceImpl implements IOrderService {

	private final OrderRepository orderRepository;
	private final BusinessRepository businessRepository;
	private final ProductRepository productRepository;
	private final IOrderKitchenMapper iOrderKitchenMapper;

	
	public OrderServiceImpl(OrderRepository orderRepository, BusinessRepository businessRepository,
			ProductRepository productRepository, IOrderKitchenMapper iOrderKitchenMapper) {
		super();
		this.orderRepository = orderRepository;
		this.businessRepository = businessRepository;
		this.productRepository = productRepository;
		this.iOrderKitchenMapper = iOrderKitchenMapper;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public OrderResponseDTO createOrder(OrderRequestDTO request) {

		if (request == null) {
			throw new RuntimeException("Pedido inválido");
		}

		if (request.getBusinessId() == null) {
			throw new RuntimeException("RestaurantId es obligatorio");
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
		order.setBusinessId(request.getBusinessId());
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

			if (!product.getBusinessId().equals(request.getBusinessId())) {
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

		Integer lastOrderNumber = orderRepository.findMaxOrderNumberByBusiness(request.getBusinessId());

		order.setOrderNumber(lastOrderNumber + 1);

		Order savedOrder = orderRepository.save(order);

		OrderResponseDTO response = new OrderResponseDTO();
		response.setOrderId(savedOrder.getId());
		response.setStatus(savedOrder.getStatus().name());

		return response;
	}

	@Override
	public List<OrderKitchenDTO> getActiveOrders(Long businessId) {
		List<Order> orders = orderRepository.findActiveOrders(businessId);
		return iOrderKitchenMapper.toListDTO(orders);
	}

	@Override
	public Order updateStatus(Long orderId, OrderStatus status) {
		Order order = orderRepository.findById(orderId).orElseThrow(() -> new RuntimeException("Pedido no encontrado"));

		validateTransition(order.getStatus(), status);

		order.setStatus(status);

		return orderRepository.save(order);
	}

	private void validateTransition(OrderStatus current, OrderStatus next) {

		if (current == OrderStatus.NEW && next != OrderStatus.PREPARING) {
			throw new RuntimeException("Transición inválida: NEW -> " + next);
		}

		if (current == OrderStatus.PREPARING && next != OrderStatus.READY) {
			throw new RuntimeException("Transición inválida: PREPARING -> " + next);
		}

		if (current == OrderStatus.READY && next != OrderStatus.DELIVERED) {
			throw new RuntimeException("Transición inválida: READY -> " + next);
		}

	}

	@Override
	public List<OrderKitchenDTO> getActiveOrdersBySlug(String slug) {
		Business restaurant = businessRepository.findBySlug(slug)
	            .orElseThrow(() -> new RuntimeException("Restaurant not found"));
		
		List<Order> orders = orderRepository.findActiveOrders(restaurant.getId());
		return iOrderKitchenMapper.toListDTO(orders);
	}
}
