package com.carrito.saas.service.impl;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.carrito.saas.dto.CategoryDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.security.SecurityService;
import com.carrito.saas.service.interfaces.ICategoryService;
import com.carrito.saas.service.mapper.interfaces.ICategoryMapper;

@Service
public class CategoryServiceImpl implements ICategoryService {

	private final CategoryRepository categoryRepository;
	private final BusinessRepository businessRepository;
	private final ICategoryMapper iCategoryMapper;
	private final SecurityService securityService;

	public CategoryServiceImpl(CategoryRepository categoryRepository, BusinessRepository businessRepository,
			ICategoryMapper iCategoryMapper, SecurityService securityService) {
		this.categoryRepository = categoryRepository;
		this.businessRepository = businessRepository;
		this.iCategoryMapper = iCategoryMapper;
		this.securityService = securityService;
	}

	@Override
	@Transactional
	public CategoryDTO crearCategoria(CategoryDTO dto) {

		Long businessId = securityService.getCurrentBusinessId();
		
	
		if (dto.getName() == null || dto.getName().isBlank())
			throw new BusinessException("El nombre de la categoría es obligatorio", ErrorType.VALIDATION);

		Business business = businessRepository.findById(businessId)
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

		String normalizedName = dto.getName().trim();

		// Validamos existencia
		boolean exists = categoryRepository.existsByNameIgnoreCaseAndBusiness(normalizedName, business.getId());

		if (exists) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Ya existe una categoría con ese nombre para este negocio");
		}

		Category c = new Category();
		c.setName(normalizedName);
		c.setOrden(dto.getOrder());
		c.setBusiness(business);
		c.setActive(true);

		return iCategoryMapper.toDTO(categoryRepository.save(c));
	}

	@Override
	@Transactional
	public CategoryDTO actualizarCategoria(Long id, CategoryDTO dto) {

		// Mismas validaciones que la creación: el nombre es obligatorio y se
		// normaliza antes de tocar la entidad.
		if (dto.getName() == null || dto.getName().isBlank())
			throw new BusinessException("El nombre de la categoría es obligatorio", ErrorType.VALIDATION);

		// El tenant ancla en el JWT: un id de categoría ajeno al negocio
		// resuelve como 404, nunca como un éxito silencioso, y la fila ajena
		// queda exactamente igual.
		Long businessId = securityService.getCurrentBusinessId();

		Category c = categoryRepository.findByIdAndBusinessId(id, businessId)
				.orElseThrow(() -> new BusinessException("Categoría no encontrada", ErrorType.NOT_FOUND));

		String normalizedName = dto.getName().trim();

		// Validamos duplicado dentro del negocio, excluyendo la propia
		// categoría (renombrar manteniendo su nombre no es duplicado).
		boolean exists = categoryRepository.existsByNameIgnoreCaseAndBusinessAndIdNot(normalizedName, businessId, id);

		if (exists) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Ya existe una categoría con ese nombre para este negocio");
		}

		c.setName(normalizedName);

		if (dto.getOrder() != null)
			c.setOrden(dto.getOrder());

		if (dto.getActive() != null)
			c.setActive(dto.getActive());

		return iCategoryMapper.toDTO(categoryRepository.save(c));
	}

	@Override
	@Transactional
	public void ordenarCategorias(List<Long> idsOrdenados) {
		// El tenant ancla en el JWT: reordenar con un id ajeno al negocio
		// resuelve como 404 y la transacción completa se descarta, dejando el
		// orden de todas las filas exactamente como estaba.
		Long businessId = securityService.getCurrentBusinessId();

		for (int i = 0; i < idsOrdenados.size(); i++) {
			Category c = categoryRepository.findByIdAndBusinessId(idsOrdenados.get(i), businessId)
					.orElseThrow(() -> new BusinessException("Categoría no encontrada", ErrorType.NOT_FOUND));
			c.setOrden(i + 1);
			categoryRepository.save(c);
		}

	}

	@Override
	public List<CategoryDTO> findAllByBusinessId() {
		Long businessId = securityService.getCurrentBusinessId();
		return iCategoryMapper.toListDTO(categoryRepository.findByBusiness_IdAndActiveTrueOrderByOrdenAsc(businessId));
	}

	@Override
	public List<CategoryDTO> findAllByBusinessIdAndActiveTrueOrFalse() {
		Long businessId = securityService.getCurrentBusinessId();
		return iCategoryMapper.toListDTO(categoryRepository.findByBusiness_IdOrderByOrdenAsc(businessId));
	}

	@Override
	public CategoryDTO findById(Long id) {
		// El tenant ancla en el JWT: leer por id una categoría ajena al
		// negocio resuelve como 404, nunca filtra la fila ajena.
		Category categoria = categoryRepository
				.findByIdAndBusinessId(id, securityService.getCurrentBusinessId())
                .orElseThrow(() -> new BusinessException("Categoría no encontrada", ErrorType.NOT_FOUND));
		return iCategoryMapper.toDTO(categoria);
	}

}

