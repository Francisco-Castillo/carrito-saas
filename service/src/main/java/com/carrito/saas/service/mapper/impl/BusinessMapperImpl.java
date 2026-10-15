package com.carrito.saas.service.mapper.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.BusinessDTO;
import com.carrito.saas.dto.CategoryDTO;
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
        entity.setLogoUrl(dto.getLogoUrl());
        entity.setBannerUrl(dto.getBannerUrl());
        entity.setAddress(dto.getAddress());
        entity.setInstagramUrl(dto.getInstagramUrl());

        return entity;
	}

	@Override
	public BusinessDTO toDTO(Business entity) {
		BusinessDTO dto = new BusinessDTO();

        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setSlug(entity.getSlug());
        dto.setWhatsappNumber(entity.getPhone());
        dto.setLogoUrl(entity.getLogoUrl());
        dto.setBannerUrl(entity.getBannerUrl());
        dto.setAddress(entity.getAddress());
        dto.setInstagramUrl(entity.getInstagramUrl());
        // Las horas las completa el llamador (MenuServiceImpl las trae del
        // repositorio); el mapper solo mapea campos de la propia entidad.
        dto.setHours(null);

        return dto;
	}

	@Override
	public List<BusinessDTO> toListDTO(List<Business> entities) {
		List<BusinessDTO> lista = new ArrayList<BusinessDTO>();
		entities.forEach(entity -> lista.add(toDTO(entity)));
		return lista;
	}

}
