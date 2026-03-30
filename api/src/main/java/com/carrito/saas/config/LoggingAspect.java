package com.carrito.saas.config;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.carrito.saas.dto.CategoryDTO;
import com.carrito.saas.dto.ComboDTO;
import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.dto.ProductCreateDTO;
import com.carrito.saas.dto.ProductDTO;
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

	    //  SIN args en start
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

	        //  SOLO ACÁ logueás args
	        String argsFiltered = Arrays.stream(joinPoint.getArgs())
	                .filter(this::isLoggable)
	                .map(this::safeToString)
	                .collect(Collectors.joining(","));

	        log.error("evento=service_error metodo={} tiempoMs={} businessId={} userId={} corrId={} tipoError={} error={} args={}",
	                method, time, businessId, userId, correlationId,
	                e.getClass().getSimpleName(),
	                e.getMessage(),
	                argsFiltered,
	                e);

	        throw e;
	    }
	}



	private boolean isLoggable(Object arg) {
		return arg instanceof ProductDTO || arg instanceof CategoryDTO || arg instanceof ComboDTO || arg instanceof ProductCreateDTO
				|| arg instanceof OrderDTO || arg instanceof OrderRequestDTO || arg instanceof Long; // IDs también
																										// sirven
	}
	
	private String safeToString(Object obj) {
	    try {
	        return obj.toString();
	    } catch (Exception e) {
	        return "error_toString";
	    }
	}

}
