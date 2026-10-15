package com.carrito.saas.service.mapper.interfaces;

import java.util.List;

public interface ICarritoMapper<E, T> {

	T toDTO(E entity);

	List<T> toListDTO(List<E> entities);

}
