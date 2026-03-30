package com.carrito.saas.loggin;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.carrito.saas.tenant.TenantContext;

import lombok.extern.slf4j.Slf4j;

@Aspect
@Component
@Slf4j
public class LoggingAspect {

	@Around("execution(* com.carrito.saas.service..*(..))")
	public Object logService(ProceedingJoinPoint joinPoint) throws Throwable {

	    String method = joinPoint.getSignature().toShortString();

	    Long businessId = TenantContext.get();
	    String userId = MDC.get("userId");
	    String correlationId = MDC.get("correlationId");

	    long start = System.currentTimeMillis();

	    log.info("evento=service_start metodo={} businessId={} userId={} corrId={}",
	            method, businessId, userId, correlationId);

	    try {
	        Object result = joinPoint.proceed();

	        long time = System.currentTimeMillis() - start;

	        log.info("evento=service_ok metodo={} tiempoMs={} businessId={} userId={} corrId={}",
	                method, time, businessId, userId, correlationId);

	        return result;

	    } catch (Exception e) {

	        long time = System.currentTimeMillis() - start;

	        log.error("evento=service_error metodo={} tiempoMs={} businessId={} userId={} corrId={} tipoError={} error={}",
	                method, time, businessId, userId, correlationId,
	                e.getClass().getSimpleName(),
	                e.getMessage(), e);

	        throw e;
	    }
	}

}
