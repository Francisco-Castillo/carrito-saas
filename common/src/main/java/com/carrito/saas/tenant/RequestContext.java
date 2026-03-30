package com.carrito.saas.tenant;

import lombok.Data;

@Data
public class RequestContext {
	
	private String tenantId;
    private String userId;
    private String correlationId;

}
