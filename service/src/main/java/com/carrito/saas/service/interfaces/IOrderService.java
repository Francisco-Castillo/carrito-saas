package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.OrderKitchenDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.dto.OrderResponseDTO;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.enums.OrderStatus;

public interface IOrderService {
	
	OrderResponseDTO createOrder(OrderRequestDTO request);
	
	List<OrderKitchenDTO> getActiveOrders(Long businessId);
	
	List<OrderKitchenDTO> getActiveOrdersBySlug(String slug);
	
	Order updateStatus(Long orderId, OrderStatus status);
	
	

}
