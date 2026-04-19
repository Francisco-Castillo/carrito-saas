package com.carrito.saas.security;

import org.springframework.stereotype.Component;

@Component("tenantKey")
public class TenantKeyGenerator {

	private final ISecurityService securityService;

	public TenantKeyGenerator(ISecurityService securityService) {
		this.securityService = securityService;
	}

	public String generate(int limit) {
		return securityService.getCurrentBusinessId() + "-" + limit;
	}

}
