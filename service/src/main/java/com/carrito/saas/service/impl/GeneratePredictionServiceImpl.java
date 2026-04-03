package com.carrito.saas.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.TodayPredictionDTO;
import com.carrito.saas.repository.jdbc.PredictionWriteRepository;
import com.carrito.saas.service.interfaces.IGeneratePredictionService;
import com.carrito.saas.service.interfaces.IPredictionService;


@Service
@Transactional
public class GeneratePredictionServiceImpl implements IGeneratePredictionService {

	private final PredictionWriteRepository writeRepository;
	private final IPredictionService predictionService;

	public GeneratePredictionServiceImpl(PredictionWriteRepository writeRepository,
			IPredictionService predictionService) {
		this.writeRepository = writeRepository;
		this.predictionService = predictionService;
	}

	@Override
	public void execute(Long businessId) {
		
		TodayPredictionDTO p = predictionService.getTodayForBusiness(businessId);

		writeRepository.replaceDailyMetric(businessId, p.getPredictedOrders(), p.getPeakHour(), p.getTopProductId());

		writeRepository.replaceHourly(businessId);
		writeRepository.replaceProducts(businessId);

	}

}
