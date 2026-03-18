package com.carrito.saas.service.mapper.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderItemDTO;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.service.mapper.interfaces.IOrderMapper;

@Component
public class OrderMapperImpl implements IOrderMapper {

	@Override
	public Order toEntity(OrderDTO dto) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public OrderDTO toDTO(Order entity) {
		if (entity == null) {
			return null;
		}
		
		OrderDTO orderDTO = new OrderDTO();
		orderDTO.setOrderId(entity.getId());
		orderDTO.setBusinessId(entity.getBusinessId());
		orderDTO.setCustomerName(entity.getCustomerName());
		orderDTO.setCustomerPhone(entity.getCustomerPhone());
		orderDTO.setCustomerAddress(entity.getCustomerAddress());
		orderDTO.setOrderType(entity.getOrderType().toString());
		orderDTO.setPaymentMethod(entity.getPaymentMethod().toString());
		orderDTO.setStatus(entity.getStatus().toString());
		orderDTO.setCreatedAt(entity.getCreatedAt());
		orderDTO.setUpdatedAt(entity.getUpdatedAt());
		orderDTO.setOrderNumber(entity.getOrderNumber());
		orderDTO.setPreparingAt(entity.getPreparingAt());
		orderDTO.setReadyAt(entity.getReadyAt());
		orderDTO.setCompletedAt(entity.getCompletedAt());
		orderDTO.setNotes(entity.getNotes());
		
		if (entity.getItems()!=null && !entity.getItems().isEmpty()) {
			List<OrderItemDTO> itemsDTO = new ArrayList<OrderItemDTO>();
			for (OrderItem orderItem : entity.getItems()) {
				OrderItemDTO item = new OrderItemDTO();
				item.setProductId(orderItem.getProductId());
				item.setQuantity(orderItem.getQuantity());
				item.setProductName(orderItem.getProductName());
				itemsDTO.add(item);
			}
			
			orderDTO.setItems(itemsDTO);
		}
		
		return orderDTO;
	}

	@Override
	public List<OrderDTO> toListDTO(List<Order> entities) {
		// TODO Auto-generated method stub
		return null;
	}

	

}
