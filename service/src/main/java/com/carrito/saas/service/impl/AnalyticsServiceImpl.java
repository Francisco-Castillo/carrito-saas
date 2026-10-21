package com.carrito.saas.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.reportes.SalesByDayDTO;
import com.carrito.saas.dto.reportes.SalesByHourDTO;
import com.carrito.saas.dto.reportes.TopProductDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
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
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

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
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

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
	@Override
	public List<SalesByDayDTO> getSalesLast7Days(String slug) {
		Business restaurant = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

		// Ventana de los últimos 7 días INCLUDING hoy: [hoy-6 00:00, mañana 00:00).
		LocalDate today = LocalDate.now();
		LocalDateTime start = today.minusDays(6).atStartOfDay();
		LocalDateTime end = today.plusDays(1).atStartOfDay();

		List<Object[]> results =
				orderRepository.findSalesByDay(restaurant.getId(), start, end);

		Map<LocalDate, Double> revenueByDay = results.stream()
				.collect(Collectors.toMap(
						r -> LocalDate.parse(String.valueOf(r[0])),
						r -> ((Number) r[1]).doubleValue()
				));

		// Siempre devolver los 7 días, con 0 en los días sin ventas: el gráfico
		// necesita la serie completa para que el eje X sea estable.
		List<SalesByDayDTO> sales = new ArrayList<>();
		for (LocalDate day = today.minusDays(6); !day.isAfter(today); day = day.plusDays(1)) {
			double revenue = revenueByDay.getOrDefault(day, 0.0);
			sales.add(new SalesByDayDTO(day.toString(), revenue));
		}

		return sales;
	}
	@Override
	public List<SalesByDayDTO> getSalesPrevious7Days(String slug) {
		Business restaurant = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

		// Semana anterior completa: [hoy-13 00:00, hoy-6 00:00). Es la ventana
		// que el gráfico compara contra getSalesLast7Days ([hoy-6, mañana)).
		LocalDate today = LocalDate.now();
		LocalDateTime start = today.minusDays(13).atStartOfDay();
		LocalDateTime end = today.minusDays(6).atStartOfDay();

		List<Object[]> results =
				orderRepository.findSalesByDay(restaurant.getId(), start, end);

		Map<LocalDate, Double> revenueByDay = results.stream()
				.collect(Collectors.toMap(
						r -> LocalDate.parse(String.valueOf(r[0])),
						r -> ((Number) r[1]).doubleValue()
				));

		// Mismo orden que la semana actual (día más viejo primero) para que el
		// dataset comparativo del gráfico alinee sin remapear en el frontend.
		// 7 días exactos: [hoy-13 .. hoy-7] — hoy-6 ya pertenece a la semana actual.
		List<SalesByDayDTO> sales = new ArrayList<>();
		for (LocalDate day = today.minusDays(13); day.isBefore(today.minusDays(6)); day = day.plusDays(1)) {
			double revenue = revenueByDay.getOrDefault(day, 0.0);
			sales.add(new SalesByDayDTO(day.toString(), revenue));
		}

		return sales;
	}

}
