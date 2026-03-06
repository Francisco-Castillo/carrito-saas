package com.carrito.saas.dto.mapper.impl;

import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.mapper.interfaces.OrderMapper;
import com.carrito.saas.repository.entity.Order;

@Component
public class OrderMapperImpl implements OrderMapper {

	@Override
	public Order toEntity(OrderDTO dto) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public OrderDTO toDTO(Order entity) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<OrderDTO> toListDTO(List<Order> entities) {
		// TODO Auto-generated method stub
		return null;
	}

}
