package com.carrito.saas.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.HourlyDTO;
import com.carrito.saas.dto.TodayPredictionDTO;
import com.carrito.saas.service.interfaces.IGeneratePredictionsForAllBusinessesUseCase;
import com.carrito.saas.service.interfaces.IPredictionService;

@RestController
@RequestMapping("/api/predictions")
public class PredictionController {

	private final IPredictionService predictionService;
	private final IGeneratePredictionsForAllBusinessesUseCase useCase;

	
	public PredictionController(IPredictionService predictionService,
			IGeneratePredictionsForAllBusinessesUseCase useCase) {
		this.predictionService = predictionService;
		this.useCase = useCase;
	}

	@GetMapping("/today")
	public TodayPredictionDTO today() {
		return predictionService.getToday();
	}

	@GetMapping("/hourly")
	public List<HourlyDTO> hourly() {
		return predictionService.getHourly();
	}

	/**
	 * REMOVED (was GET /products): answered 200 with a null body. The service
	 * stub and the repository query it would need were never implemented, and
	 * both are now deleted rather than left as a promise. It needs real
	 * repository support for "top products" before it can answer anything
	 * honest; until then the path must not exist.
	 */
	@PostMapping("/run")
	public void run() {
	    useCase.execute();
	}

	/**
	 * REMOVED (was GET /admin/hourly?businessId=): answered 200 with a null
	 * body, and it cannot be made honest today — the securityUtils.isAdmin()
	 * guard it needs does not exist anywhere in the codebase (no role checking
	 * at all), and the businessId parameter would let any caller read another
	 * business' hourly distribution. It needs an admin authorization design
	 * that scopes or rejects cross-business reads before it can come back.
	 */

}
