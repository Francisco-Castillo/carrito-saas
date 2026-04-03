package com.carrito.saas.jobs;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.carrito.saas.service.interfaces.IGeneratePredictionsForAllBusinessesUseCase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduler encargado de ejecutar automáticamente la generación de predicciones.
 *
 * <p>Este componente:</p>
 * <ul>
 *     <li>No contiene lógica de negocio</li>
 *     <li>No accede a repositorios</li>
 *     <li>Solo orquesta la ejecución del caso de uso</li>
 * </ul>
 *
 * <p>Se ejecuta mediante una expresión CRON configurable.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PredictionScheduler {
	
	private final IGeneratePredictionsForAllBusinessesUseCase useCase;

	 /**
     * Ejecuta el job de predicción de forma programada.
     *
     * <p>Por defecto corre todos los días a las 04:00 AM.</p>
     *
     * <p>Configuración:</p>
     * <pre>
     * app.predictions.cron=0 0 4 * * *
     * </pre>
     */
    @Scheduled(cron = "${app.predictions.cron:0 0 4 * * *}")
    public void run() {

        log.info("Starting prediction job");

        useCase.execute();

        log.info("Prediction job finished");
    }

}
