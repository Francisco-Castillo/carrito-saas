package com.carrito.saas.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.service.interfaces.IProductService;
import com.carrito.saas.service.mapper.interfaces.IProductMapper;

@Service
public class ProductServiceImpl implements IProductService {

	private final ProductRepository productRepository;
	private final IProductMapper iProductMapper;

	public ProductServiceImpl(ProductRepository productRepository, IProductMapper iProductMapper) {
		this.productRepository = productRepository;
		this.iProductMapper = iProductMapper;
	}

	@Override
	public List<ProductDTO> getProductsByRestaurant(Long restaurantId) {
		  List<Product> products = productRepository.findByBusinessIdAndActiveTrue(restaurantId);
		  return iProductMapper.toListDTO(products);
	}

}
