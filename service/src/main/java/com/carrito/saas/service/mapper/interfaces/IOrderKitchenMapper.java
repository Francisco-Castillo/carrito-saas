package com.carrito.saas.service.mapper.interfaces;

import java.util.List;

import com.carrito.saas.dto.OrderKitchenDTO;
import com.carrito.saas.repository.entity.Order;

public interface IOrderKitchenMapper {
	
	List<OrderKitchenDTO> toListDTO(List<Order> orders);

}
