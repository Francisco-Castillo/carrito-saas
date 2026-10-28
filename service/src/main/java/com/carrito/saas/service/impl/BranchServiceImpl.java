package com.carrito.saas.service.impl;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.BranchDTO;
import com.carrito.saas.dto.SelectBranchResponseDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Branch;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BranchRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.security.JwtUtil;
import com.carrito.saas.security.SecurityService;
import com.carrito.saas.service.interfaces.IBranchService;
import com.carrito.saas.service.mapper.interfaces.IBranchMapper;

/**
 * Sucursales del negocio del JWT (multi-sucursal, odd/tasks/multi-sucursal.md,
 * T2). Mismo contrato de tenant que {@code CategoryServiceImpl}: el negocio
 * ancla en el JWT, un id ajeno resuelve 404 y jamás un éxito silencioso, y la
 * baja es lógica ({@code active=false}), nunca borrado físico.
 *
 * <p>WhatsApp por sucursal (multi-sucursal, odd/tasks/multi-sucursal.md,
 * S3/T5): el {@code phone} es opcional y guarda dígitos E.164 sin '+' — el
 * mismo formato canónico que {@code businesses.phone}, con la misma regla de
 * validación (rePLICADA de {@code BusinessProfileServiceImpl#validatePhone}:
 * método privado, no compartible sin tocar superficies ajenas a esta
 * unidad). El número identifica un LUGAR, así que la unicidad es global —
 * propia o ajena— y el guard por repositorio + el constraint
 * {@code uq_branches_phone} son defensa en profundidad: el HTTP responde 409
 * y jamás llega al 500 del constraint.</p>
 */
@Service
public class BranchServiceImpl implements IBranchService {

	/**
	 * Espeja el check businesses_phone_shape y su validación en código:
	 * dígitos E.164 sin '+', sin 0 ni 15 inicial. REPLICADA de
	 * {@code BusinessProfileServiceImpl.PHONE_SHAPE} (método privado, fuente
	 * citada por el encabezado de la clase) — mantener ambas en sync.
	 */
	private static final Pattern PHONE_SHAPE = Pattern.compile("^[1-9][0-9]{7,13}$");

	private final BranchRepository branchRepository;
	private final BusinessRepository businessRepository;
	private final IBranchMapper branchMapper;
	private final SecurityService securityService;
	private final JwtUtil jwtUtil;

	public BranchServiceImpl(BranchRepository branchRepository, BusinessRepository businessRepository,
			IBranchMapper branchMapper, SecurityService securityService, JwtUtil jwtUtil) {
		this.branchRepository = branchRepository;
		this.businessRepository = businessRepository;
		this.branchMapper = branchMapper;
		this.securityService = securityService;
		this.jwtUtil = jwtUtil;
	}

	@Override
	@Transactional(readOnly = true)
	public List<BranchDTO> listar() {
		return branchMapper.toListDTO(branchRepository.findByBusinessIdOrderByIdAsc(currentBusinessId()));
	}

	@Override
	@Transactional
	public BranchDTO crear(BranchDTO dto) {

		Long businessId = currentBusinessId();

		String name = validName(dto);
		String phone = validPhone(dto.getPhone());

		if (branchRepository.existsByBusinessIdAndNameIgnoreCase(businessId, name))
			throw new BusinessException("Ya existe una sucursal con ese nombre para este negocio", ErrorType.CONFLICT);

		// El número identifica un lugar (S3/T5): unicidad global — el 409 es
		// para el dueño que quiere reusar el WhatsApp de otra sucursal, propia
		// o ajena; el constraint uq_branches_phone es la segunda línea.
		if (phone != null && branchRepository.existsByPhone(phone))
			throw new BusinessException("Ya existe una sucursal con ese número de WhatsApp", ErrorType.CONFLICT);

		Business business = businessRepository.findById(businessId)
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

		Branch branch = new Branch();
		branch.setBusiness(business);
		branch.setName(name);
		branch.setAddress(blankToNull(dto.getAddress()));
		branch.setPhone(phone);

		return branchMapper.toDTO(branchRepository.save(branch));
	}

	@Override
	@Transactional
	public BranchDTO actualizar(Long id, BranchDTO dto) {

		Long businessId = currentBusinessId();

		String name = validName(dto);
		String phone = validPhone(dto.getPhone());

		// Un id ajeno al negocio resuelve como no encontrado: la fila ajena
		// queda exactamente igual (verificarlo es parte del contrato).
		Branch branch = branchRepository.findByIdAndBusinessId(id, businessId)
				.orElseThrow(() -> new BusinessException("Sucursal no encontrada", ErrorType.NOT_FOUND));

		if (branchRepository.existsByBusinessIdAndNameIgnoreCaseAndIdNot(businessId, name, id))
			throw new BusinessException("Ya existe una sucursal con ese nombre para este negocio", ErrorType.CONFLICT);

		// IdNot excluye la propia fila: mantener el número que ya tenía la
		// sucursal es legítimo, tomar el de OTRA (propia o ajena) es 409.
		if (phone != null && branchRepository.existsByPhoneAndIdNot(phone, id))
			throw new BusinessException("Ya existe una sucursal con ese número de WhatsApp", ErrorType.CONFLICT);

		branch.setName(name);
		branch.setAddress(blankToNull(dto.getAddress()));
		branch.setPhone(phone);

		return branchMapper.toDTO(branchRepository.save(branch));
	}

	@Override
	@Transactional
	public BranchDTO eliminar(Long id) {

		Branch branch = branchRepository.findByIdAndBusinessId(id, currentBusinessId())
				.orElseThrow(() -> new BusinessException("Sucursal no encontrada", ErrorType.NOT_FOUND));

		// Guard de última activa (S3): sin sucursales activas el panel y la
		// carta caen a la vista a nivel negocio y los pedidos nuevos no tienen
		// destino. Dar de baja una inactiva (idempotente) o una entre varias
		// activas sigue funcionando igual.
		if (Boolean.TRUE.equals(branch.getActive()) && branchRepository
				.findByBusinessIdAndActiveTrueOrderByIdAsc(currentBusinessId()).size() <= 1)
			throw new BusinessException("No podés dar de baja la última sucursal activa", ErrorType.CONFLICT);

		// Baja lógica: reversible, el historial (pedidos, ventas) no se toca.
		branch.setActive(Boolean.FALSE);

		return branchMapper.toDTO(branchRepository.save(branch));
	}

	@Override
	@Transactional
	public BranchDTO reactivar(Long id) {

		// Sucursal propia: un id ajeno resuelve como no encontrado (la fila
		// ajena queda exactamente igual, verificarlo es parte del contrato).
		Branch branch = branchRepository.findByIdAndBusinessId(id, currentBusinessId())
				.orElseThrow(() -> new BusinessException("Sucursal no encontrada", ErrorType.NOT_FOUND));

		// Reactivar una ya activa es idempotente: 200 con el DTO, no un error.
		branch.setActive(Boolean.TRUE);

		return branchMapper.toDTO(branchRepository.save(branch));
	}

	@Override
	@Transactional
	public SelectBranchResponseDTO seleccionar(Long id) {

		Long businessId = currentBusinessId();

		// Sucursal propia y activa: un id ajeno y uno dado de baja resuelven
		// igual (404) — no se revela si el id existe en otro negocio.
		Branch branch = branchRepository.findByIdAndBusinessId(id, businessId)
				.filter(b -> Boolean.TRUE.equals(b.getActive()))
				.orElseThrow(() -> new BusinessException("Sucursal no encontrada", ErrorType.NOT_FOUND));

		org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder
				.getContext().getAuthentication();
		String username = auth.getName();

		String token = jwtUtil.generateToken(username, businessId, branch.getId());
		long expiresAt = System.currentTimeMillis() + jwtUtil.getExpirationMillis();

		return new SelectBranchResponseDTO(token, branch.getId(), branch.getName(), expiresAt);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Long currentBusinessId() {
		return securityService.getCurrentBusinessId();
	}

	private String validName(BranchDTO dto) {

		if (dto.getName() == null || dto.getName().isBlank())
			throw new BusinessException("El nombre de la sucursal es obligatorio", ErrorType.VALIDATION);

		return dto.getName().trim();
	}

	private String blankToNull(String address) {
		return address == null || address.isBlank() ? null : address.trim();
	}

	/**
	 * WhatsApp de la sucursal (S3/T5): opcional — null/blank es legítimo (la
	 * sucursal cae al número del negocio en la carta) — y presente tiene que
	 * ser dígitos E.164 sin '+', misma regla y mensaje que el teléfono del
	 * perfil del negocio ({@code BusinessProfileServiceImpl#validatePhone},
	 * lógica replicada, ver el encabezado de la clase).
	 */
	private String validPhone(String phone) {

		if (phone == null || phone.isBlank())
			return null;

		String trimmed = phone.trim();
		if (!PHONE_SHAPE.matcher(trimmed).matches())
			throw new BusinessException("El teléfono debe ser código de país y número, sin 0 ni 15",
					ErrorType.VALIDATION);

		return trimmed;
	}

}
