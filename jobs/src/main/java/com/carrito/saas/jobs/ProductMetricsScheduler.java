package com.carrito.saas.jobs;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.carrito.saas.service.usecase.IAggregateProductMetricsUseCase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProductMetricsScheduler {

	private final IAggregateProductMetricsUseCase useCase;

	@Scheduled(fixedRate = 300000) // 5 min
	public void run() {
		log.info("Starting product metrics aggregation");

		useCase.execute();

		log.info("Product metrics aggregation finished");
	}

}
