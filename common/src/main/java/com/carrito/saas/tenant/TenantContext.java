package com.carrito.saas.tenant;

public class TenantContext {

	private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

	 public static void set(Long businessId) {
	        CURRENT.set(businessId);
	    }

	    public static Long get() {
	        return CURRENT.get();
	    }

	    public static void clear() {
	        CURRENT.remove();
	    }

}
