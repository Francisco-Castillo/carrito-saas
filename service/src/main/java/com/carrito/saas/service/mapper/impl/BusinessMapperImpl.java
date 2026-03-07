package com.carrito.saas.service.mapper.impl;

import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.BusinessDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.service.mapper.interfaces.IBusinessMapper;

@Component
public class BusinessMapperImpl implements IBusinessMapper{

	@Override
	public Business toEntity(BusinessDTO dto) {
		Business entity = new Business();

        entity.setId(dto.getId());
        entity.setName(dto.getName());
        entity.setSlug(dto.getSlug());
        entity.setPhone(dto.getWhatsappNumber());

        return entity;
	}

	@Override
	public BusinessDTO toDTO(Business entity) {
		BusinessDTO dto = new BusinessDTO();

        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setSlug(entity.getSlug());
        dto.setWhatsappNumber(entity.getPhone());

        return dto;
	}

	@Override
	public List<BusinessDTO> toListDTO(List<Business> entities) {
		// TODO Auto-generated method stub
		return null;
	}

}
