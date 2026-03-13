package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.OrderKitchenDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.dto.OrderResponseDTO;

public interface IOrderService {
	
	OrderResponseDTO createOrder(OrderRequestDTO request);
	
	List<OrderKitchenDTO> getActiveOrders(Long businessId);

}
