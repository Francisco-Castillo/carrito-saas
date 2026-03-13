package com.carrito.saas.service.mapper.impl;

import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.service.mapper.interfaces.IOrderMapper;

@Component
public class OrderMapperImpl implements IOrderMapper {

	@Override
	public Order toEntity(OrderRequestDTO dto) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public OrderRequestDTO toDTO(Order entity) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<OrderRequestDTO> toListDTO(List<Order> entities) {
		// TODO Auto-generated method stub
		return null;
	}

}
