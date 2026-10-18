package com.carrito.saas.service.mapper.interfaces;

import java.util.List;

import com.carrito.saas.dto.OrderManagementDTO;
import com.carrito.saas.repository.entity.Order;

public interface IOrderManagementMapper {

	List<OrderManagementDTO> toListDTO(List<Order> orders);

}
