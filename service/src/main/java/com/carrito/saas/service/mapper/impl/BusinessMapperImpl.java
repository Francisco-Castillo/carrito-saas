package com.carrito.saas.service.mapper.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.BusinessDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.service.mapper.interfaces.IBusinessMapper;

@Component
public class BusinessMapperImpl implements IBusinessMapper{

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
        dto.setPrimaryColor(entity.getPrimaryColor());
        dto.setMenuFont(entity.getMenuFont());
        // Las horas y las fechas especiales las completa el llamador
        // (MenuServiceImpl las trae del repositorio); el mapper solo mapea
        // campos de la propia entidad.
        dto.setHours(null);
        dto.setDateExceptions(null);

        return dto;
	}

	@Override
	public List<BusinessDTO> toListDTO(List<Business> entities) {
		List<BusinessDTO> lista = new ArrayList<BusinessDTO>();
		entities.forEach(entity -> lista.add(toDTO(entity)));
		return lista;
	}

}
