package com.carrito.saas.config;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.carrito.saas.tenant.TenantContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class TenantContextFilter extends OncePerRequestFilter{

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		 

		String correlationId = Optional.ofNullable(request.getHeader("X-Correlation-ID"))
		        .orElse(UUID.randomUUID().toString());


		// Logs estructurados

		MDC.put("correlationId", correlationId);

		response.setHeader("X-Correlation-ID", correlationId);

	        try {
	        	filterChain.doFilter(request, response);
	        } finally {
	            TenantContext.clear();
	            MDC.clear();
	        }
	    }

}
