package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderKitchenDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.repository.enums.OrderStatus;

public interface IOrderService {


	OrderDTO createOrder(OrderRequestDTO request);

	List<OrderKitchenDTO> getActiveOrders(Long businessId);

	List<OrderKitchenDTO> getActiveOrders();

	OrderDTO updateStatus(Long orderId, OrderStatus status);

}
