package com.carrito.saas.service.mapper.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.CancellationReasonDTO;
import com.carrito.saas.repository.entity.CancellationReason;
import com.carrito.saas.service.mapper.interfaces.ICancellationReasonMapper;

@Component
public class ICancellationReasonMapperImpl implements ICancellationReasonMapper{

	@Override
	public CancellationReason toEntity(CancellationReasonDTO dto) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CancellationReasonDTO toDTO(CancellationReason entity) {
		if (entity == null) {
			return null;
		}
		
		CancellationReasonDTO cancellationReasonDTO = new CancellationReasonDTO();
		cancellationReasonDTO.setId(entity.getId());
		cancellationReasonDTO.setCode(entity.getCode());
		cancellationReasonDTO.setDescription(entity.getDescription());
		cancellationReasonDTO.setActive(entity.getActive());
		cancellationReasonDTO.setCreatedAt(entity.getCreatedAt());
		return cancellationReasonDTO;
	}

	@Override
	public List<CancellationReasonDTO> toListDTO(List<CancellationReason> entities) {
		List<CancellationReasonDTO> lista = new ArrayList<CancellationReasonDTO>();
		entities.forEach(entity -> lista.add(toDTO(entity)));
		return lista;
	}

}
