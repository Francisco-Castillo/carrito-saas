package com.carrito.saas.service.mapper.impl;

import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.CategoryDTO;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.service.mapper.interfaces.ICategoryMapper;

@Component
public class CategoryMapperImpl implements ICategoryMapper {

	@Override
	public Category toEntity(CategoryDTO dto) {
		Category entity = new Category();

		entity.setId(dto.getId());
		entity.setName(dto.getName());

		return entity;
	}

	@Override
	public CategoryDTO toDTO(Category entity) {
		CategoryDTO dto = new CategoryDTO();

		dto.setId(entity.getId());
		dto.setName(entity.getName());

		return dto;
	}

	@Override
	public List<CategoryDTO> toListDTO(List<Category> entities) {
		// TODO Auto-generated method stub
		return null;
	}

}
