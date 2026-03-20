package com.carrito.saas.service.mapper.impl;

import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.ComboDTO;
import com.carrito.saas.dto.ComboItemDTO;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.service.mapper.interfaces.IComboMapper;

@Component
public class IComboMapperImpl implements IComboMapper {

	@Override
	public Combo toEntity(ComboDTO dto) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ComboDTO toDTO(Combo entity) {
		ComboDTO dto = new ComboDTO();

		dto.setId(entity.getId());
		dto.setName(entity.getName());
		dto.setPrice(entity.getPrice());
		dto.setCategoryId(entity.getCategory().getId());

		List<ComboItemDTO> items = entity.getItems().stream().map(cp -> {
			ComboItemDTO item = new ComboItemDTO();
			item.setProductName(cp.getProduct().getName());
			item.setQuantity(cp.getQuantity());
			return item;
		}).toList();

		dto.setItems(items);

		return dto;
	}

	@Override
	public List<ComboDTO> toListDTO(List<Combo> entities) {
		// TODO Auto-generated method stub
		return null;
	}

}
