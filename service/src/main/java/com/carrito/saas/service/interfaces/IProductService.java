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

	/**
	 * Guarda (o reemplaza) la imagen de un producto propio del negocio
	 * autenticado. La validación de tipo/tamaño y la escritura en disco las
	 * hace {@code ImageStorageService}; en la entidad queda la URL pública
	 * servida por {@code /api/images/**}.
	 *
	 * @param id          id del producto.
	 * @param data        contenido del archivo.
	 * @param contentType content type declarado (image/jpeg, image/png o image/webp).
	 * @return el DTO actualizado con la nueva imageUrl.
	 */
	ProductDTO actualizarImagen(Long id, byte[] data, String contentType);

	/** Quita la imagen de un producto propio; si no tiene, no cambia nada. */
	ProductDTO eliminarImagen(Long id);

}
