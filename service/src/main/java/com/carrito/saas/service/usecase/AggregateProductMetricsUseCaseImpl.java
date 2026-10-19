package com.carrito.saas.service.usecase;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.repository.jdbc.ProductMetricsRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Transactional
@Slf4j
public class AggregateProductMetricsUseCaseImpl implements IAggregateProductMetricsUseCase {

	private final ProductMetricsRepository productMetricsRepository;

	/**
	 * Ventana (en días hacia atrás desde hoy) que agrega el job de métricas
	 * sobre {@code product_daily_sales}. NO puede ser solo "hoy": las
	 * tendencias comparan la semana actual contra la anterior (ventanas de 14
	 * días), así que agregar únicamente el día de hoy dejaría la feature de
	 * tendencias vacía durante dos semanas en una base nueva.
	 */
	private final int windowDays;

	public AggregateProductMetricsUseCaseImpl(
			ProductMetricsRepository productMetricsRepository,
			@Value("${app.metrics.window-days:30}") int windowDays) {
		this.productMetricsRepository = productMetricsRepository;
		this.windowDays = windowDays;
	}

	@Override
	public void execute() {
		LocalDate endDate = LocalDate.now();
		LocalDate startDate = endDate.minusDays(windowDays);

		log.info("Aggregating product daily sales from {} to {} (window-days={})",
				startDate, endDate, windowDays);

		productMetricsRepository.aggregateRange(startDate, endDate);
	}

}
