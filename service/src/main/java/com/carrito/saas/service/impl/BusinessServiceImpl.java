package com.carrito.saas.service.impl;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.BusinessDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
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
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

		// Baja lógica (S5 de odd/tasks/plataforma-editar-baja.md): un local
		// dado de baja NO existe públicamente — 404, no 403. Es el lookup
		// público del login/carta; los scopes internos del dueño no pasan por
		// acá y quedan intactos.
		if (!Boolean.TRUE.equals(business.getActive())) {
			throw new BusinessException("Local no encontrado", ErrorType.NOT_FOUND);
		}

		return iBusinessMapper.toDTO(business);

	}

}
