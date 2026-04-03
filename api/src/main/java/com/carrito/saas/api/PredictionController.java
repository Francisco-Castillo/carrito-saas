package com.carrito.saas.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

	@GetMapping("/products")
	public List<Map<String, Object>> products() {
		//return predictionService.getProducts(businessId);
		return null;
	}
	
	@PostMapping("/run")
	public void run() {
	    useCase.execute();
	}
	
	@GetMapping("/admin/hourly")
	public List<HourlyDTO> hourly(@RequestParam Long businessId) {
		/*
	    if (!securityUtils.isAdmin()) {
	        throw new ForbiddenException();
	    }

	    return predictionService.getHourlyForBusiness(businessId);*/
		return null;
	}

}
