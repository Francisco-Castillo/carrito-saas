package com.carrito.saas.service.impl;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.carrito.saas.dto.CategoryDTO;
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
			throw new RuntimeException("Nombre requerido");

		Business business = businessRepository.findById(businessId)
				.orElseThrow(() -> new RuntimeException("Restaurante no encontrado"));

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
		Category c = categoryRepository.findById(id).orElseThrow(() -> new RuntimeException("Categoria no encontrada"));

		if (dto.getName() != null)
			c.setName(dto.getName());

		if (dto.getOrder() != null)
			c.setOrden(dto.getOrder());

		if (dto.getActive() != null)
			c.setActive(dto.getActive());

		return iCategoryMapper.toDTO(categoryRepository.save(c));
	}

	@Override
	@Transactional
	public void ordenarCategorias(List<Long> idsOrdenados) {
		for (int i = 0; i < idsOrdenados.size(); i++) {
			Category c = categoryRepository.findById(idsOrdenados.get(i))
					.orElseThrow(() -> new RuntimeException("Categoria no encontrada"));
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
		Category categoria = categoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Categoría no encontrada con id: " + id));
		return iCategoryMapper.toDTO(categoria);
	}

}

