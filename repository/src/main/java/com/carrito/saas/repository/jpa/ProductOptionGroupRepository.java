package com.carrito.saas.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.ProductOptionGroup;

@Repository
public interface ProductOptionGroupRepository extends JpaRepository<ProductOptionGroup, Long> {

	List<ProductOptionGroup> findByProductIdOrderByIdAsc(Long productId);

	// Reemplazo completo de grupos al actualizar el producto (S1): borrar los
	// viejos y persistir los que llegan en el PUT. El WHERE por product_id
	// además blinda el tenant: solo se borran grupos del producto propio.
	@Modifying
	void deleteByProductId(Long productId);
}
