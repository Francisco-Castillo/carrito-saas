package com.carrito.saas.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.CancellationReasonDTO;
import com.carrito.saas.service.interfaces.ICancellationReasonService;

@RestController
@RequestMapping("/api")
public class CancellationReasonController {

	private final ICancellationReasonService iCancellationReasonService;

	public CancellationReasonController(ICancellationReasonService iCancellationReasonService) {
		this.iCancellationReasonService = iCancellationReasonService;
	}

	@GetMapping("/cancellation-reasons")
	public List<CancellationReasonDTO> getReasons() {
		return iCancellationReasonService.getReasons();
	}

}
