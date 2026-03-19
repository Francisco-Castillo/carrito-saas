package com.carrito.saas.service.interfaces;

import com.carrito.saas.dto.BusinessDTO;

public interface IBusinessService {
	
	BusinessDTO getBusinessBySlug(String slug);

}
