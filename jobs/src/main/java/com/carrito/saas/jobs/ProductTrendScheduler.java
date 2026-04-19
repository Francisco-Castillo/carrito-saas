package com.carrito.saas.jobs;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.carrito.saas.service.usecase.IProductTrendUseCase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProductTrendScheduler {

	private final IProductTrendUseCase useCase;

	@Scheduled(cron = "0 */10 * * * *") // cada 10 min
	public void updateProductTrends() {
		log.info("Updating product trends...");
		useCase.execute();
		log.info("Updating product trends finished...");
	}

}
