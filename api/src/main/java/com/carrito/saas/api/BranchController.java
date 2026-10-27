package com.carrito.saas.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.BranchDTO;
import com.carrito.saas.dto.SelectBranchResponseDTO;
import com.carrito.saas.exception.ErrorResponse;
import com.carrito.saas.repository.entity.Branch;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BranchRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.security.ISecurityService;
import com.carrito.saas.service.interfaces.IBranchService;
import com.carrito.saas.service.interfaces.IQrCodeService;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Sucursales del negocio del JWT (multi-sucursal, odd/tasks/multi-sucursal.md,
 * T2). Igual que el resto del panel del dueño, no hay id ni slug de negocio en
 * la ruta: el negocio sale del JWT, así que un negocio nunca ve ni toca las
 * sucursales de otro. {@code DELETE} es baja lógica (reversible), nunca
 * borrado físico.
 */
@RestController
@RequestMapping("/api/business/branches")
public class BranchController {

	private final IBranchService branchService;

	private final ISecurityService securityService;

	private final BranchRepository branchRepository;

	private final BusinessRepository businessRepository;

	private final IQrCodeService qrCodeService;

	private final IMenuUrlBuilder menuUrlBuilder;

	public BranchController(IBranchService branchService, ISecurityService securityService,
			BranchRepository branchRepository, BusinessRepository businessRepository,
			IQrCodeService qrCodeService, IMenuUrlBuilder menuUrlBuilder) {
		this.branchService = branchService;
		this.securityService = securityService;
		this.branchRepository = branchRepository;
		this.businessRepository = businessRepository;
		this.qrCodeService = qrCodeService;
		this.menuUrlBuilder = menuUrlBuilder;
	}

	@GetMapping
	public List<BranchDTO> listar() {
		return branchService.listar();
	}

	@PostMapping
	public BranchDTO crear(@RequestBody BranchDTO dto) {
		return branchService.crear(dto);
	}

	@PutMapping("/{id}")
	public BranchDTO actualizar(@PathVariable Long id, @RequestBody BranchDTO dto) {
		return branchService.actualizar(id, dto);
	}

	@DeleteMapping("/{id}")
	public BranchDTO eliminar(@PathVariable Long id) {
		return branchService.eliminar(id);
	}

	/**
	 * Selecciona la sucursal activa del usuario (T3): devuelve un token nuevo
	 * con el claim {@code branchId}. El negocio sale del JWT; un id ajeno o
	 * inactivo resuelve 404.
	 */
	@PostMapping("/select/{id}")
	public SelectBranchResponseDTO seleccionar(@PathVariable Long id) {
		return branchService.seleccionar(id);
	}

	/**
	 * PNG QR de la carta de UNA sucursal (multi-sucursal,
	 * odd/tasks/multi-sucursal.md, S3/T6): réplica del contrato de
	 * {@code BusinessController#getCurrentBusinessQr} con la identidad
	 * la sucursal. El negocio sale del JWT (como en todo el panel); la
	 * sucursal debe ser PROPIA y ACTIVA — un id ajeno, inexistente o dado de
	 * baja resuelve SIEMPRE 404, sin revelar cuál de las tres razones (el
	 * mensaje es único). El QR codifica la URL del menú del grupo con el
	 * parámetro público {@code ?sucursal=<id>} (nunca un slug inventado); el
	 * storefront lo lee y el checkout lo manda como {@code branchId} (S4).
	 *
	 * <p>El QR del grupo ({@code GET /api/business/qr}) NO cambia: sigue
	 * siendo el material impreso default del negocio sin sucursal.</p>
	 *
	 * <p>Los estados de falla se devuelven directos (no lanzados): el catch-all
	 * {@code GlobalExceptionHandler} remapea cualquier
	 * {@code ResponseStatusException} a 500, que le ocultaría el estado
	 * honesto al dueño — mismo motivo del comentario en
	 * {@code getCurrentBusinessQr}. 404 directo con {@link ErrorResponse};
	 * 503 cuando {@code generateQr} devuelve null (jamás un 200 vacío).
	 *
	 * @return {@code image/png} con el QR de la sucursal; 404 si es ajena,
	 *         inexistente o inactiva; 503 si falla la generación
	 */
	@GetMapping("/{id}/qr")
	public ResponseEntity<?> qrDeSucursal(@PathVariable Long id, HttpServletRequest request) {

		Long businessId = securityService.getCurrentBusinessId();

		// Propia y activa, en una sola consulta (mismo guard fail-closed del
		// resto del panel): ajena, inexistente e inactiva son el mismo 404.
		Branch branch = branchRepository.findByIdAndBusinessId(id, businessId).orElse(null);
		if (branch == null || !Boolean.TRUE.equals(branch.getActive())) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new ErrorResponse(404, "Not Found",
							"Sucursal no encontrada", request.getRequestURI()));
		}

		Business business = businessRepository.findById(businessId).orElse(null);
		if (business == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new ErrorResponse(404, "Not Found",
							"Business " + businessId + " not found", request.getRequestURI()));
		}

		String menuUrl = menuUrlBuilder.buildMenuUrl(business.getSlug(), branch.getId());

		byte[] qr = qrCodeService.generateQr(menuUrl, null);

		if (qr == null) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body(new ErrorResponse(503, "Service Unavailable",
							"QR code generation failed for menu URL " + menuUrl,
							request.getRequestURI()));
		}

		return ResponseEntity.ok()
				.contentType(MediaType.IMAGE_PNG)
				.body(qr);
	}

}
