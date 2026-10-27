package com.carrito.saas.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.BranchDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Branch;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BranchRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.security.SecurityService;
import com.carrito.saas.service.interfaces.IBranchService;
import com.carrito.saas.service.mapper.interfaces.IBranchMapper;

/**
 * Sucursales del negocio del JWT (multi-sucursal, odd/tasks/multi-sucursal.md,
 * T2). Mismo contrato de tenant que {@code CategoryServiceImpl}: el negocio
 * ancla en el JWT, un id ajeno resuelve 404 y jamás un éxito silencioso, y la
 * baja es lógica ({@code active=false}), nunca borrado físico.
 */
@Service
public class BranchServiceImpl implements IBranchService {

	private final BranchRepository branchRepository;
	private final BusinessRepository businessRepository;
	private final IBranchMapper branchMapper;
	private final SecurityService securityService;

	public BranchServiceImpl(BranchRepository branchRepository, BusinessRepository businessRepository,
			IBranchMapper branchMapper, SecurityService securityService) {
		this.branchRepository = branchRepository;
		this.businessRepository = businessRepository;
		this.branchMapper = branchMapper;
		this.securityService = securityService;
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

		if (branchRepository.existsByBusinessIdAndNameIgnoreCase(businessId, name))
			throw new BusinessException("Ya existe una sucursal con ese nombre para este negocio", ErrorType.CONFLICT);

		Business business = businessRepository.findById(businessId)
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

		Branch branch = new Branch();
		branch.setBusiness(business);
		branch.setName(name);
		branch.setAddress(blankToNull(dto.getAddress()));

		return branchMapper.toDTO(branchRepository.save(branch));
	}

	@Override
	@Transactional
	public BranchDTO actualizar(Long id, BranchDTO dto) {

		Long businessId = currentBusinessId();

		String name = validName(dto);

		// Un id ajeno al negocio resuelve como no encontrado: la fila ajena
		// queda exactamente igual (verificarlo es parte del contrato).
		Branch branch = branchRepository.findByIdAndBusinessId(id, businessId)
				.orElseThrow(() -> new BusinessException("Sucursal no encontrada", ErrorType.NOT_FOUND));

		if (branchRepository.existsByBusinessIdAndNameIgnoreCaseAndIdNot(businessId, name, id))
			throw new BusinessException("Ya existe una sucursal con ese nombre para este negocio", ErrorType.CONFLICT);

		branch.setName(name);
		branch.setAddress(blankToNull(dto.getAddress()));

		return branchMapper.toDTO(branchRepository.save(branch));
	}

	@Override
	@Transactional
	public BranchDTO eliminar(Long id) {

		Branch branch = branchRepository.findByIdAndBusinessId(id, currentBusinessId())
				.orElseThrow(() -> new BusinessException("Sucursal no encontrada", ErrorType.NOT_FOUND));

		// Baja lógica: reversible, el historial (pedidos, ventas) no se toca.
		branch.setActive(Boolean.FALSE);

		return branchMapper.toDTO(branchRepository.save(branch));
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

}
