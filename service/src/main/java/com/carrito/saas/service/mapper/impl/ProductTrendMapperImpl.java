package com.carrito.saas.service.mapper.impl;

import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.ProductTrendDTO;
import com.carrito.saas.repository.entity.ProductTrend;
import com.carrito.saas.service.mapper.interfaces.IProductTrendMapper;

@Component
public class ProductTrendMapperImpl implements IProductTrendMapper {

	@Override
	public ProductTrend toEntity(ProductTrendDTO dto) {
		// TODO Auto-generated method stub
		return null;
	}

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
		// TODO Auto-generated method stub
		return null;
	}

}
