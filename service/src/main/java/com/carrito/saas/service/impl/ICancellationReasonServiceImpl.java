package com.carrito.saas.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.CancellationReasonDTO;
import com.carrito.saas.repository.entity.CancellationReason;
import com.carrito.saas.repository.jpa.CancellationReasonRepository;
import com.carrito.saas.service.interfaces.ICancellationReasonService;
import com.carrito.saas.service.mapper.interfaces.ICancellationReasonMapper;

@Service
public class ICancellationReasonServiceImpl implements ICancellationReasonService {

	private final CancellationReasonRepository cancellationReasonRepository;
	private final ICancellationReasonMapper iCancellationReasonMapper;

	public ICancellationReasonServiceImpl(CancellationReasonRepository cancellationReasonRepository,
			ICancellationReasonMapper iCancellationReasonMapper) {
		this.cancellationReasonRepository = cancellationReasonRepository;
		this.iCancellationReasonMapper = iCancellationReasonMapper;
	}

	@Override
	public List<CancellationReasonDTO> getReasons() {
		List<CancellationReason> lista = cancellationReasonRepository.findByActiveTrue();
		return iCancellationReasonMapper.toListDTO(lista);
	}

}
