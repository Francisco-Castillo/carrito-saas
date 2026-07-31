package com.carrito.saas.service.mapper.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.TableResponseDTO;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.TableStatus;
import com.carrito.saas.service.mapper.interfaces.IRestaurantTableMapper;

@Component
public class RestaurantTableMapperImpl implements IRestaurantTableMapper {

	@Override
	public RestaurantTable toEntity(TableResponseDTO dto) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public TableResponseDTO toDTO(RestaurantTable entity) {
		TableResponseDTO response =  new TableResponseDTO();
		response.setId(entity.getId());
		response.setTableNumber(entity.getTableNumber());
		response.setTableName(entity.getTableName());
		response.setQrToken(entity.getQrToken());
		response.setQrUrl("");
		response.setCreatedAt(entity.getCreatedAt());
		response.setStatus(TableStatus.AVAILABLE);
		return response;
	}

	@Override
	public List<TableResponseDTO> toListDTO(List<RestaurantTable> entities) {
		List<TableResponseDTO> lista = new ArrayList<TableResponseDTO>();
		entities.forEach(entity -> lista.add(toDTO(entity)));
		return lista;
	}

}
