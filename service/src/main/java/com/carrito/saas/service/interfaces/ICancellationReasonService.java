package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.CancellationReasonDTO;

public interface ICancellationReasonService {
	
	 List<CancellationReasonDTO> getReasons();

}
