package com.carrito.saas.service.mapper.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.service.mapper.interfaces.IProductMapper;

import jakarta.persistence.Entity;

@Component
public class ProductMapperImpl implements IProductMapper {

	@Override
	public Product toEntity(ProductDTO dto) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ProductDTO toDTO(Product entity) {
		ProductDTO dto = new ProductDTO();

		dto.setId(entity.getId());
		dto.setName(entity.getName());
		dto.setDescription(entity.getDescription());
		dto.setPrice(entity.getPrice());
		dto.setActive(entity.isActive());
		
		dto.setCategoryId(entity.getCategoryId());
		dto.setImageUrl(entity.getImageUrl());

		

		return dto;
	}

	@Override
	public List<ProductDTO> toListDTO(List<Product> entities) {
		List<ProductDTO> lista = new ArrayList<ProductDTO>();
		entities.forEach(entity -> lista.add(toDTO(entity)));
		return lista;
	}

}


