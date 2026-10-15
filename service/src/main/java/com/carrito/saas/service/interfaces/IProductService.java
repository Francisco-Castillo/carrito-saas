package com.carrito.saas.service.interfaces;



import java.util.List;

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

	/**
	 * Listado del panel de administración: TODOS los productos del negocio
	 * autenticado (activos e inactivos, el panel muestra una columna de
	 * estado), anclado al businessId del JWT.
	 *
	 * @return listado completo de productos propios.
	 */
	List<ProductDTO> findAllByBusinessId();

	/**
	 * Activa o desactiva un producto propio del negocio autenticado.
	 *
	 * @param id     id del producto.
	 * @param activo nuevo valor de la bandera active.
	 * @return el DTO actualizado.
	 * @throws com.carrito.saas.exception.BusinessException NOT_FOUND si el
	 *                                                      producto no existe
	 *                                                      o es ajeno.
	 */
	ProductDTO cambiarEstadoActivo(Long id, boolean activo);

}
