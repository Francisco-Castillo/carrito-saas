package com.carrito.saas.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.BusinessDTO;
import com.carrito.saas.dto.BusinessProfileDTO;
import com.carrito.saas.dto.OrderKitchenDTO;
import com.carrito.saas.dto.OrderManagementDTO;
import com.carrito.saas.exception.ErrorResponse;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.security.ISecurityService;
import com.carrito.saas.service.interfaces.IBusinessProfileService;
import com.carrito.saas.service.interfaces.IBusinessService;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;
import com.carrito.saas.service.interfaces.IOrderService;
import com.carrito.saas.service.interfaces.IQrCodeService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/business")
public class BusinessController {

	private IOrderService orderService;
	private final IBusinessService iBusinessService;

	private final BusinessRepository businessRepository;

	private final ISecurityService securityService;

	private final IBusinessProfileService businessProfileService;

	private final IQrCodeService qrCodeService;

	private final IMenuUrlBuilder menuUrlBuilder;

	public BusinessController(IOrderService orderService, BusinessRepository businessRepository,
			ISecurityService securityService, IBusinessProfileService businessProfileService,
			IQrCodeService qrCodeService, IMenuUrlBuilder menuUrlBuilder, IBusinessService iBusinessService) {
		super();
		this.orderService = orderService;
		this.businessRepository = businessRepository;
		this.securityService = securityService;
		this.businessProfileService = businessProfileService;
		this.qrCodeService = qrCodeService;
		this.menuUrlBuilder = menuUrlBuilder;
		this.iBusinessService = iBusinessService;
	}

	@GetMapping("/orders/active")
	public List<OrderKitchenDTO> getActiveOrders() {
		return orderService.getActiveOrders();
	}

	/**
	 * Vista de gestión de pedidos del día (mostrador): TODOS los estados,
	 * incluidos Entregados y Cancelados con motivo — a diferencia del KDS
	 * ({@code /orders/active}). El negocio sale del JWT, nunca del request;
	 * {@code date} es opcional (YYYY-MM-DD, default hoy).
	 */
	@GetMapping("/orders")
	public List<OrderManagementDTO> getOrdersByDay(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return orderService.getOrdersByDay(date);
	}

	@GetMapping("/slug/{slug}")
	public BusinessDTO getBySlug(@PathVariable String slug) {
		return iBusinessService.getBusinessBySlug(slug);
	}

	/**
	 * Storefront profile del negocio del JWT (logo, banner, dirección,
	 * Instagram y horas de apertura estructuradas, ordenadas lunes a
	 * domingo). El business sale de {@link ISecurityService#getCurrentBusinessId()};
	 * no hay id ni slug del request, así un negocio nunca lee el perfil de
	 * otro. 404 cuando el JWT no corresponde a una fila de negocio.
	 */
	@GetMapping("/profile")
	public BusinessProfileDTO getProfile() {
		return businessProfileService.getProfile();
	}

	/**
	 * Edición del storefront profile del negocio del JWT. La lista de horas
	 * se reemplaza en bloque (un día ausente del payload se borra); los
	 * campos de URL se validan (http(s) absoluto o ruta relativa) y los
	 * errores de validación salen como 400 con mensaje en español.
	 */
	@PutMapping("/profile")
	public BusinessProfileDTO updateProfile(@RequestBody BusinessProfileDTO profile) {
		return businessProfileService.updateProfile(profile);
	}

	/**
	 * PNG QR code that encodes the current owner's public menu URL
	 * ({@code <base>/menu/index.html?restaurant=<slug>}), ready to print for the
	 * pickup/delivery channel.
	 *
	 * <p>The business is derived from the authenticated JWT via
	 * {@link ISecurityService#getCurrentBusinessId()}; there is deliberately no
	 * client-supplied id or slug parameter, so one business can never fetch
	 * another's QR.</p>
	 *
	 * <p>Failure statuses are returned directly (not thrown): the catch-all
	 * {@code GlobalExceptionHandler} re-maps any {@code ResponseStatusException}
	 * to 500, which would hide the honest status from the owner.</p>
	 *
	 * @return {@code image/png} with the QR bytes; 404 when the JWT's business
	 *         has no row; 503 when QR generation fails ({@code
	 *         ZxingQrCodeServiceImpl} signals failure by returning {@code null},
	 *         which must never reach the caller as an empty 200).
	 */
	@GetMapping("/qr")
	public ResponseEntity<?> getCurrentBusinessQr(HttpServletRequest request) {

		Long businessId = securityService.getCurrentBusinessId();

		Optional<Business> business = businessRepository.findById(businessId);

		if (business.isEmpty()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new ErrorResponse(404, "Not Found",
							"Business " + businessId + " not found", request.getRequestURI()));
		}

		String menuUrl = menuUrlBuilder.buildMenuUrl(business.get().getSlug());

		byte[] qr = qrCodeService.generateQr(menuUrl, null);

		if (qr == null) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body(new ErrorResponse(503, "Service Unavailable",
							"QR code generation failed for menu URL " + menuUrl, request.getRequestURI()));
		}

		return ResponseEntity.ok()
				.contentType(MediaType.IMAGE_PNG)
				.body(qr);
	}

}