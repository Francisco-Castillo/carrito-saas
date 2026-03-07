package com.carrito.saas.service.impl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.CategoryDTO;
import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.service.interfaces.IMenuService;
import com.carrito.saas.service.mapper.interfaces.IBusinessMapper;
import com.carrito.saas.service.mapper.interfaces.ICategoryMapper;
import com.carrito.saas.service.mapper.interfaces.IProductMapper;

@Service
public class MenuServiceImpl implements IMenuService {

	private final BusinessRepository businessRepository;
	private final CategoryRepository categoryRepository;
	private final ProductRepository productRepository;
	private final IBusinessMapper iBusinessMapper;
	private final ICategoryMapper iCategoryMapper;
	private final IProductMapper iProductMapper;

	public MenuServiceImpl(BusinessRepository businessRepository, CategoryRepository categoryRepository,
			ProductRepository productRepository, IBusinessMapper iBusinessMapper, ICategoryMapper iCategoryMapper,
			IProductMapper iProductMapper) {
		super();
		this.businessRepository = businessRepository;
		this.categoryRepository = categoryRepository;
		this.productRepository = productRepository;
		this.iBusinessMapper = iBusinessMapper;
		this.iCategoryMapper = iCategoryMapper;
		this.iProductMapper = iProductMapper;
	}

	@Override
	public MenuDTO getMenu(String slug) {
		Business business = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new RuntimeException("Business not found"));

		List<Category> categories = categoryRepository.findByBusinessId(business.getId());

		List<Product> products = productRepository.findByBusinessIdAndActiveTrue(business.getId());

		MenuDTO menu = new MenuDTO();

		menu.setBusiness(iBusinessMapper.toDTO(business));

		List<CategoryDTO> categoryDTOs = categories.stream().map(iCategoryMapper::toDTO).collect(Collectors.toList());

		menu.setCategories(categoryDTOs);

		List<ProductDTO> productDTOs = products.stream().map(iProductMapper::toDTO).collect(Collectors.toList());

		menu.setProducts(productDTOs);

		return menu;
	}

}
