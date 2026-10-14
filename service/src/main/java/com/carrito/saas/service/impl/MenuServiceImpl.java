package com.carrito.saas.service.impl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.BusinessDTO;
import com.carrito.saas.dto.BusinessHoursDTO;
import com.carrito.saas.dto.CategoryDTO;
import com.carrito.saas.dto.ComboDTO;
import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessHours;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.service.interfaces.IMenuService;
import com.carrito.saas.service.mapper.interfaces.IBusinessMapper;
import com.carrito.saas.service.mapper.interfaces.ICategoryMapper;
import com.carrito.saas.service.mapper.interfaces.IComboMapper;
import com.carrito.saas.service.mapper.interfaces.IProductMapper;

@Service
public class MenuServiceImpl implements IMenuService {

	private final BusinessRepository businessRepository;
	private final BusinessHoursRepository businessHoursRepository;
	private final CategoryRepository categoryRepository;
	private final ProductRepository productRepository;
	private final ComboRepository comboRepository;
	private final IBusinessMapper iBusinessMapper;
	private final ICategoryMapper iCategoryMapper;
	private final IProductMapper iProductMapper;
	private final IComboMapper iComboMapper;

	public MenuServiceImpl(BusinessRepository businessRepository, BusinessHoursRepository businessHoursRepository,
			CategoryRepository categoryRepository,
			ProductRepository productRepository, ComboRepository comboRepository, IBusinessMapper iBusinessMapper,
			ICategoryMapper iCategoryMapper, IProductMapper iProductMapper, IComboMapper iComboMapper) {
		this.businessRepository = businessRepository;
		this.businessHoursRepository = businessHoursRepository;
		this.categoryRepository = categoryRepository;
		this.productRepository = productRepository;
		this.comboRepository = comboRepository;
		this.iBusinessMapper = iBusinessMapper;
		this.iCategoryMapper = iCategoryMapper;
		this.iProductMapper = iProductMapper;
		this.iComboMapper = iComboMapper;
	}

	@Override
	public MenuDTO getMenu(String slug) {
		Business business = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

		List<Category> categories = categoryRepository.findByBusinessId(business.getId());

		// Buscamos los productos del negocio que esten activos y ofrecibles:
		// stock null (infinito) o stock > 0; stock 0 no se ofrece.
		List<Product> products = productRepository.findPublicMenuProducts(business.getId());

		// NUEVO: traer combos
		List<Combo> combos = comboRepository.findFullMenuCombos(business.getId());

		MenuDTO menu = new MenuDTO();

		menu.setBusiness(iBusinessMapper.toDTO(business));

		// Storefront: las horas se traen acá (la entidad Business no las
		// colecciona) para que el menú no necesite una segunda llamada. La
		// consulta ya ordena lunes→domingo (1..7); sin horas, lista vacía —
		// nunca null.
		List<BusinessHoursDTO> hoursDTOs = businessHoursRepository
				.findByBusiness_IdOrderByDayOfWeekAsc(business.getId())
				.stream()
				.map(MenuServiceImpl::toHoursDTO)
				.collect(Collectors.toList());

		menu.getBusiness().setHours(hoursDTOs);

		List<CategoryDTO> categoryDTOs = categories.stream().map(iCategoryMapper::toDTO).collect(Collectors.toList());

		menu.setCategories(categoryDTOs);

		List<ProductDTO> productDTOs = products.stream().map(iProductMapper::toDTO).collect(Collectors.toList());

		menu.setProducts(productDTOs);

		// NUEVO: mapear combos
		List<ComboDTO> comboDTOs = combos.stream().map(iComboMapper::toDTO).collect(Collectors.toList());

		menu.setCombos(comboDTOs);

		return menu;
	}

	private static BusinessHoursDTO toHoursDTO(BusinessHours hours) {
		BusinessHoursDTO dto = new BusinessHoursDTO();
		dto.setDayOfWeek(hours.getDayOfWeek());
		dto.setOpensAt(hours.getOpensAt());
		dto.setClosesAt(hours.getClosesAt());
		dto.setClosed(hours.isClosed());
		return dto;
	}

}
