package com.carrito.saas.dto.mapper.interfaces;

import java.util.List;

public interface CarritoMapper<E, T> {

	E toEntity(T dto);

	T toDTO(E entity);

	List<T> toListDTO(List<E> entities);

}
