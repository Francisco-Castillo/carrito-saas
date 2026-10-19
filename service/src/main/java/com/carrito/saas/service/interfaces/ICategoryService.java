package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.CategoryDTO;

public interface ICategoryService {

	CategoryDTO crearCategoria(CategoryDTO dto);

	CategoryDTO actualizarCategoria(Long id, CategoryDTO dto);

	void ordenarCategorias(List<Long> idsOrdenados);

	/**
	 * Metodo que retorna listado de categorias activas y ordenadas de un negocio.
	 * Para Frontend (clientes)
	 * 
	 * @return listado de categorias activas
	 */
	List<CategoryDTO> findAllByBusinessId();

	/**
	 * Método encargado de retornar listado de categorias ordenadas con estado
	 * activo o inactivo de un determinado restaurante.
	 * 
	 * Usado por admin.
	 * 
	 * @return listado de categorias.
	 */
	List<CategoryDTO> findAllByBusinessIdAndActiveTrueOrFalse();
	
	CategoryDTO findById(Long id);

	/**
	 * Elimina una categoría del negocio del JWT. Una categoría que todavía
	 * tiene productos se rechaza con 409 (nunca se hace cascade hacia los
	 * productos); un id ajeno al negocio resuelve como 404.
	 */
	void eliminarCategoria(Long id);

}
