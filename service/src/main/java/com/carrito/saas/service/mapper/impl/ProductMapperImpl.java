package com.carrito.saas.service.mapper.impl;

import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.dto.ProductOptionDTO;
import com.carrito.saas.dto.ProductOptionGroupDTO;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.entity.ProductOption;
import com.carrito.saas.repository.entity.ProductOptionGroup;
import com.carrito.saas.service.mapper.interfaces.IProductMapper;


@Component
public class ProductMapperImpl implements IProductMapper {

	@Override
	public ProductDTO toDTO(Product entity) {
		ProductDTO dto = new ProductDTO();

		dto.setId(entity.getId());
		dto.setName(entity.getName());
		dto.setDescription(entity.getDescription());
		dto.setPrice(entity.getPrice());
		dto.setCost(entity.getCost());
		dto.setActive(entity.isActive());
		dto.setStock(entity.getStock());
		
		dto.setCategoryId(entity.getCategory().getId());
		dto.setCategoryName(entity.getCategory().getName());
		dto.setImageUrl(entity.getImageUrl());
		dto.setUnitMeasure(entity.getUnitMeasure());

		// Grupos de opciones (odd/tasks/product-options.md S1-S2). Lazy: el
		// mapeo corre dentro del @Transactional del servicio, igual que category.
		if (entity.getOptionGroups() != null) {
			if (dto.getOptionGroups() == null)
				dto.setOptionGroups(new ArrayList<>());
			for (ProductOptionGroup group : entity.getOptionGroups()) {
				ProductOptionGroupDTO groupDto = new ProductOptionGroupDTO();
				groupDto.setId(group.getId());
				groupDto.setName(group.getName());
				groupDto.setRequired(group.isRequired());
				groupDto.setMultiple(group.isMultiple());
				groupDto.setMinSelections(group.getMinSelections());
				groupDto.setMaxSelections(group.getMaxSelections());
				for (ProductOption option : group.getOptions()) {
					ProductOptionDTO optionDto = new ProductOptionDTO();
					optionDto.setId(option.getId());
					optionDto.setName(option.getName());
					optionDto.setPriceDelta(option.getPriceDelta());
					groupDto.getOptions().add(optionDto);
				}
				dto.getOptionGroups().add(groupDto);
			}
		}

		return dto;
	}

	@Override
	public List<ProductDTO> toListDTO(List<Product> entities) {
		List<ProductDTO> lista = new ArrayList<ProductDTO>();
		entities.forEach(entity -> lista.add(toDTO(entity)));
		return lista;
	}

}


