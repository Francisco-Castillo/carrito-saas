package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.ProductCreateDTO;
import com.carrito.saas.dto.ProductDTO;

public interface IProductService {
	
	List<ProductDTO> getProductsByRestaurant(Long restaurantId);
	
	ProductDTO crearProducto(ProductCreateDTO dto);
	
	ProductDTO actualizarProducto(Long id, ProductCreateDTO dto);
	
	void eliminarProducto(Long id);

}
