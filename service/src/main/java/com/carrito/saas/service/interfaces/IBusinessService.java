package com.carrito.saas.service.interfaces;

import com.carrito.saas.repository.entity.Business;

public interface IBusinessService {
	
	Business getBusinessBySlug(String slug);

}
