package com.carrito.saas.service.mapper.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.BranchDTO;
import com.carrito.saas.repository.entity.Branch;
import com.carrito.saas.service.mapper.interfaces.IBranchMapper;

@Component
public class BranchMapperImpl implements IBranchMapper {

	@Override
	public BranchDTO toDTO(Branch entity) {
		BranchDTO dto = new BranchDTO();

		dto.setId(entity.getId());
		dto.setName(entity.getName());
		dto.setAddress(entity.getAddress());
		dto.setPhone(entity.getPhone());
		dto.setActive(entity.getActive());
		dto.setBusinessId(entity.getBusiness() == null ? null : entity.getBusiness().getId());

		return dto;
	}

	@Override
	public List<BranchDTO> toListDTO(List<Branch> entities) {
		List<BranchDTO> lista = new ArrayList<BranchDTO>();
		entities.forEach(entity -> lista.add(toDTO(entity)));
		return lista;
	}

}
