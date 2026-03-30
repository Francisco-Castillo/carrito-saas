package com.carrito.saas.repository.entity;

import com.carrito.saas.tenant.TenantContext;

import jakarta.persistence.PrePersist;

public class TenantListener {
	
	@PrePersist
	public void setTenant(Object entity) {

	    if (entity instanceof TieneBusiness e) {
	        Business b = new Business();
	        b.setId(TenantContext.get());
	        e.setBusiness(b);
	    }
	}

}
