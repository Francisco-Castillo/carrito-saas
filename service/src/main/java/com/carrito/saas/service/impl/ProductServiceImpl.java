package com.carrito.saas.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.ProductCreateDTO;
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
		// Buscamos los productos que pertenezcan al negocio, que esten activos y tengan stock mayor a cero.
		  List<Product> products = productRepository.findByCategory_Business_IdAndActiveTrueAndStockGreaterThan(restaurantId,0);
		  return iProductMapper.toListDTO(products);
	}

	@Override
	public ProductDTO crearProducto(ProductCreateDTO dto) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ProductDTO actualizarProducto(Long id, ProductCreateDTO dto) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void eliminarProducto(Long id) {
		// TODO Auto-generated method stub
		
	}

}
