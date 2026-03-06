package com.carrito.saas.service.impl;

import org.springframework.stereotype.Service;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.service.interfaces.IBusinessService;


@Service
public class BusinessServiceImpl implements IBusinessService {
	
	private final BusinessRepository businessRepository;

    public BusinessServiceImpl(BusinessRepository businessRepository) {
        this.businessRepository = businessRepository;
    }
    
	@Override
	public Business getBusinessBySlug(String slug) {

        return businessRepository
                .findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Business not found"));
	}
	
	

}	
