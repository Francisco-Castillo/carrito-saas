package com.carrito.saas.service.impl;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.HourlyDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.dto.TodayPredictionDTO;
import com.carrito.saas.repository.jpa.PredictionRepository;
import com.carrito.saas.repository.projection.DailyAverageProjection;
import com.carrito.saas.repository.projection.HourlyProjection;
import com.carrito.saas.repository.projection.ProductProjection;
import com.carrito.saas.security.SecurityService;
import com.carrito.saas.service.interfaces.IPredictionService;

/**
 * Servicio encargado de calcular predicciones de demanda para un negocio.
 *
 * <p>
 * Este servicio soporta dos contextos:
 * </p>
 *
 * <ul>
 * <li><b>Contexto API (usuario autenticado)</b>: obtiene el businessId desde el
 * SecurityContext.</li>
 * <li><b>Contexto interno (jobs/batch)</b>: recibe el businessId
 * explícitamente.</li>
 * </ul>
 *
 * <p>
 * La lógica de predicción está centralizada en este servicio para evitar
 * duplicación y mantener una única fuente de verdad.
 * </p>
 */
@Service
public class PredictionServiceImpl implements IPredictionService {

	private final PredictionRepository predictionRepository;
	private final SecurityService securityService;

	public PredictionServiceImpl(PredictionRepository predictionRepository, SecurityService securityService) {
		this.predictionRepository = predictionRepository;
		this.securityService = securityService;
	}

	@Override
	public TodayPredictionDTO getToday() {
		Long businessId = securityService.getCurrentBusinessId();
		return calculatePrediction(businessId);
	}

	@Override
	public TodayPredictionDTO getTodayForBusiness(Long businessId) {
		return calculatePrediction(businessId);
	}

	/**
	 * Calcula la predicción de demanda utilizando datos históricos.
	 *
	 * <p>
	 * La fórmula aplicada es:
	 * </p>
	 *
	 * <pre>
	 * prediction = promedio últimos días + (tendencia * 0.4)
	 * </pre>
	 *
	 * <p>
	 * También calcula:
	 * </p>
	 * <ul>
	 * <li>Hora pico de demanda</li>
	 * <li>Producto más vendido</li>
	 * </ul>
	 *
	 * @param businessId identificador del negocio
	 * @return DTO con resultados de predicción
	 */
	private TodayPredictionDTO calculatePrediction(Long businessId) {

		Double avg = Optional.ofNullable(predictionRepository.getDailyAverage(businessId))
				.map(DailyAverageProjection::getAvgOrders).orElse(0.0);

		Double trend = Optional.ofNullable(predictionRepository.getTrend(businessId)).orElse(0.0);

		int prediction = (int) (avg + (trend * 0.4));

		Integer peakHour = Optional.ofNullable(predictionRepository.getPeakHour(businessId))
				.map(HourlyProjection::getHour).orElse(20);

		Long topProduct = Optional.ofNullable(predictionRepository.getTopProduct(businessId))
				.map(ProductProjection::getProductId).orElse(null);

		return new TodayPredictionDTO(prediction, peakHour, topProduct);
	}

	@Override
	public List<HourlyDTO> getHourly() {
		Long businessId = securityService.getCurrentBusinessId();
		return predictionRepository.getHourlyDistribution(businessId).stream()
				.map(p -> new HourlyDTO(p.getHour(), p.getOrders())).toList();
	}

	@Override
	public List<HourlyDTO> getHourlyForBusiness(Long businessId) {
		return predictionRepository.getHourlyDistribution(businessId).stream()
				.map(p -> new HourlyDTO(p.getHour(), p.getOrders())).toList();
	}

	@Override
	public List<ProductDTO> getProducts() {
		// TODO Auto-generated method stub
		Long businessId = securityService.getCurrentBusinessId();
		/*
		 * return predictionRepository.getTopProducts(businessId) .stream() .map(p ->
		 * new ProductDTO(p.getProductId(), p.getQuantity())) .toList();
		 */
		return null;
	}

	@Override
	public List<ProductDTO> getProductsForBusiness(Long businessId) {
		/*
		 * return predictionRepository.getTopProducts(businessId) .stream() .map(p ->
		 * new ProductDTO(p.getProductId(), p.getQuantity())) .toList();
		 */
		return null;
	}

}
