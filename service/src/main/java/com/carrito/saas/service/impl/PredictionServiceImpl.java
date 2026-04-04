package com.carrito.saas.service.impl;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.HourlyDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.dto.TodayPredictionDTO;
import com.carrito.saas.repository.jpa.PredictionRepository;
import com.carrito.saas.repository.projection.AverageOrdersProjection;
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
	 * Calcula la predicción de demanda diaria para un negocio a partir de datos históricos.
	 *
	 * <p>
	 * Este método utiliza métricas reales de pedidos para estimar la cantidad de órdenes
	 * esperadas para el día actual, junto con información complementaria relevante
	 * para la operación del negocio.
	 * </p>
	 *
	 * <h3>Fórmula de predicción</h3>
	 *
	 * <pre>
	 * predictedOrders = avgOrders + (trend * 0.4)
	 * </pre>
	 *
	 * Donde:
	 * <ul>
	 *   <li><b>avgOrders</b>: promedio de pedidos diarios en los últimos días</li>
	 *   <li><b>trend</b>: variación reciente en la demanda (crecimiento o caída)</li>
	 * </ul>
	 *
	 * <h3>Métricas calculadas</h3>
	 *
	 * <ul>
	 *   <li><b>Predicted Orders:</b> cantidad estimada de pedidos para hoy</li>
	 *   <li><b>Average Orders:</b> promedio histórico usado como referencia</li>
	 *   <li><b>Variation %:</b> diferencia porcentual entre la predicción y el promedio</li>
	 *   <li><b>Demand Level:</b> clasificación de la demanda (LOW, NORMAL, HIGH)</li>
	 *   <li><b>Peak Hour:</b> hora con mayor volumen histórico de pedidos</li>
	 *   <li><b>Top Product:</b> producto más vendido en el período reciente</li>
	 * </ul>
	 *
	 * <h3>Interpretación</h3>
	 *
	 * <ul>
	 *   <li>Variación positiva → mayor demanda esperada</li>
	 *   <li>Variación negativa → menor demanda esperada</li>
	 *   <li>Demand Level permite una lectura rápida del estado del negocio</li>
	 * </ul>
	 *
	 * <h3>Consideraciones</h3>
	 *
	 * <ul>
	 *   <li>Si no existen datos históricos, se utilizan valores por defecto (0 o N/A)</li>
	 *   <li>Se evita división por cero al calcular la variación porcentual</li>
	 *   <li>El producto top puede no estar disponible si no hay ventas registradas</li>
	 * </ul>
	 *
	 * <h3> Ejemplo</h3>
	 *
	 * <pre>
	 * avgOrders = 40
	 * trend = 10
	 *
	 * predictedOrders = 40 + (10 * 0.4) = 44
	 * variation = ((44 - 40) / 40) * 100 = +10%
	 *
	 * Resultado:
	 * {
	 *   predictedOrders: 44,
	 *   avgOrders: 40,
	 *   variationPercent: 10,
	 *   demandLevel: "HIGH",
	 *   peakHour: 21,
	 *   topProductName: "Burger"
	 * }
	 * </pre>
	 *
	 * @param businessId identificador único del negocio (tenant)
	 * @return {@link TodayPredictionDTO} con la predicción y métricas asociadas
	 */
	private TodayPredictionDTO calculatePrediction(Long businessId) {

		// Primedio de pedidos por dia.
		Double avg = Optional.ofNullable(predictionRepository.getDailyAverage(businessId))
				.map(DailyAverageProjection::getAvgOrders).orElse(0.0);
		// Tendencia: crecimiento o caída reciente
		Double trend = Optional.ofNullable(predictionRepository.getTrend(businessId)).orElse(0.0);
		
		// Prediccion final.
		int prediction = (int) (avg + (trend * 0.4));

		// Hora pico: hora con más pedidos históricamente
		Integer peakHour = Optional.ofNullable(predictionRepository.getPeakHour(businessId))
				.map(HourlyProjection::getHour).orElse(20);
		
		// Producto top: producto más vendido
		ProductProjection topProduct = predictionRepository.getTopProduct(businessId);

		Long productId = topProduct != null ? topProduct.getProductId() : null;
		String productName = topProduct != null ? topProduct.getProductName() : "N/A";
		
		double variation = avg == 0 ? 0 : Math.round(((prediction - avg) / avg) * 100);
		
		String demandLevel;

		if (prediction < avg * 0.8) {
		    demandLevel = "LOW";
		} else if (prediction <= avg * 1.2) {
		    demandLevel = "NORMAL";
		} else {
		    demandLevel = "HIGH";
		}
		
		if (avg == 0) {
		    demandLevel = "UNKNOWN";
		}
		
		return new TodayPredictionDTO(
		    prediction,
		    peakHour,
		    productId,
		    productName, 
		    avg,
		    variation,
		    demandLevel
		);
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

	@Override
	public Double getAverageOrders() {
		Long businessId = securityService.getCurrentBusinessId();
		return Optional.ofNullable(predictionRepository.getAverageOrdersLast7Days(businessId))
	            .map(AverageOrdersProjection::getAvgOrders)
	            .orElse(0.0);
	}

}
