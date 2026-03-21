package com.carrito.saas.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.ComboProduct;

@Repository
public interface ComboProductRepository extends JpaRepository<ComboProduct, Long> {

}
