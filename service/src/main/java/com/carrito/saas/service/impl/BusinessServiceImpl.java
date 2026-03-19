package com.carrito.saas.service.impl;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.BusinessDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.service.interfaces.IBusinessService;
import com.carrito.saas.service.mapper.interfaces.IBusinessMapper;

@Service
public class BusinessServiceImpl implements IBusinessService {

	private final BusinessRepository businessRepository;
	private final IBusinessMapper iBusinessMapper;

	public BusinessServiceImpl(BusinessRepository businessRepository, IBusinessMapper iBusinessMapper) {
		this.businessRepository = businessRepository;
		this.iBusinessMapper = iBusinessMapper;
	}

	@Override
	public BusinessDTO getBusinessBySlug(String slug) {

		Business business = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new RuntimeException("Business not found"));

		return iBusinessMapper.toDTO(business);

	}

}
