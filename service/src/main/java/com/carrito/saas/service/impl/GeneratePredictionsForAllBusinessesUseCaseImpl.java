package com.carrito.saas.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.service.interfaces.IGeneratePredictionService;
import com.carrito.saas.service.interfaces.IGeneratePredictionsForAllBusinessesUseCase;

import lombok.extern.slf4j.Slf4j;


/**
 * Implementación del caso de uso batch que procesa todos los negocios.
 *
 * <p>Responsabilidades:</p>
 * <ul>
 *     <li>Obtener lista de negocios</li>
 *     <li>Ejecutar predicción individual por cada uno</li>
 *     <li>Manejar errores sin interrumpir el procesamiento global</li>
 * </ul>
 *
 * <p>Este diseño permite tolerancia a fallos por negocio.</p>
 */
@Service
@Transactional
@Slf4j
public class GeneratePredictionsForAllBusinessesUseCaseImpl implements IGeneratePredictionsForAllBusinessesUseCase {

	private final BusinessRepository businessRepository;
	private final IGeneratePredictionService generateUseCase; //GeneratePredictionsUseCase

	public GeneratePredictionsForAllBusinessesUseCaseImpl(BusinessRepository businessRepository,
			IGeneratePredictionService generateUseCase) {
		this.businessRepository = businessRepository;
		this.generateUseCase = generateUseCase;
	}

	@Override
	public void execute() {
		List<Long> businessIds = businessRepository.findAllIds();
		businessIds.forEach(this::processSafely);

	}

	 /**
     * Ejecuta el procesamiento de un negocio con manejo de errores.
     *
     * @param businessId identificador del negocio
     */
	private void processSafely(Long businessId) {
		try {
			generateUseCase.execute(businessId);
		} catch (Exception e) {
			log.error("Error processing predictions for business {}", businessId, e);
			throw e;
		}
	}

}
