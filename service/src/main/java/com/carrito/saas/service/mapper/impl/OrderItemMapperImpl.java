package com.carrito.saas.service.mapper.impl;

import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.OrderItemDTO;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.service.mapper.interfaces.IOrderItemMapper;

@Component
public class OrderItemMapperImpl implements IOrderItemMapper {

	@Override
	public OrderItem toEntity(OrderItemDTO dto) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public OrderItemDTO toDTO(OrderItem entity) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<OrderItemDTO> toListDTO(List<OrderItem> entities) {
		// TODO Auto-generated method stub
		return null;
	}

}
