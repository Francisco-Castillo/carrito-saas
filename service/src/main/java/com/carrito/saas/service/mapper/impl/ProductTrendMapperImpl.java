package com.carrito.saas.service.mapper.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.ProductTrendDTO;
import com.carrito.saas.repository.entity.ProductTrend;
import com.carrito.saas.service.mapper.interfaces.IProductTrendMapper;

@Component
public class ProductTrendMapperImpl implements IProductTrendMapper {

	@Override
	public ProductTrendDTO toDTO(ProductTrend entity) {
		return ProductTrendDTO.builder()
                .productId(entity.getProductId())
                .productName(entity.getProductName())
                .trendPercentage(entity.getTrendPercentage())
                .trendDirection(entity.getTrendDirection().name())
                .build();
	}

	@Override
	public List<ProductTrendDTO> toListDTO(List<ProductTrend> entities) {
		List<ProductTrendDTO> lista = new ArrayList<ProductTrendDTO>();
		entities.forEach(entity -> lista.add(toDTO(entity)));
		return lista;
	}

}
