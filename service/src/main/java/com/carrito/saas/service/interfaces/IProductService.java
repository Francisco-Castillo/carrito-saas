package com.carrito.saas.service.interfaces;



import com.carrito.saas.dto.PageResponse;
import com.carrito.saas.dto.ProductCreateDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.dto.ProductFilterDTO;

public interface IProductService {

	PageResponse<ProductDTO> getProductsByRestaurant(Long restaurantId, int page, int size, String sortBy,
			String sortDir, ProductFilterDTO filter);

	ProductDTO crearProducto(ProductCreateDTO dto);

	ProductDTO actualizarProducto(Long id, ProductDTO dto);

	void eliminarProducto(Long id);

}
