package com.carrito.saas.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.reportes.SalesByHourDTO;
import com.carrito.saas.dto.reportes.TopProductDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.service.interfaces.IAnalyticsService;

@Service
public class AnalyticsServiceImpl implements IAnalyticsService {

	private final BusinessRepository businessRepository;
	private final OrderRepository orderRepository;

	public AnalyticsServiceImpl(BusinessRepository businessRepository, OrderRepository orderRepository) {
		super();
		this.businessRepository = businessRepository;
		this.orderRepository = orderRepository;
	}

	@Override
	public List<TopProductDTO> getTopProductsToday(String slug) {
		
		Business restaurant = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new RuntimeException("Restaurant not found"));

	    LocalDate today = LocalDate.now();

	    LocalDateTime start = today.atStartOfDay();
	    LocalDateTime end = today.plusDays(1).atStartOfDay();

	    List<Object[]> results =
	            orderRepository.findTopProductsToday(restaurant.getId(), start, end);

	    return results.stream()
	            .map(r -> new TopProductDTO(
	                    (String) r[0],
	                    ((Number) r[1]).longValue()
	            ))
	            .toList();
	}

	@Override
	public List<SalesByHourDTO> getSalesByHourToday(String slug) {
		Business restaurant = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new RuntimeException("Restaurant not found"));

		LocalDate today = LocalDate.now();

	    LocalDateTime start = today.atStartOfDay();
	    LocalDateTime end = today.plusDays(1).atStartOfDay();

	    List<Object[]> results =
	            orderRepository.findSalesByHourToday(restaurant.getId(), start, end);

	    // Mapear resultados de la BD a un Map<hour, revenue>
	    Map<Integer, Double> revenueByHour = results.stream()
	            .collect(Collectors.toMap(
	                    r -> ((Number) r[0]).intValue(),
	                    r -> ((Number) r[1]).doubleValue()
	            ));

	    List<SalesByHourDTO> sales = new ArrayList<>();

	    // Generar las 24 horas del día
	    for (int h = 0; h < 24; h++) {

	        String hourLabel = String.format("%02d:00", h);

	        double revenue = revenueByHour.getOrDefault(h, 0.0);

	        sales.add(new SalesByHourDTO(hourLabel, revenue));
	    }

	    return sales;
	}

}
