package com.carrito.saas.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.Product;

import jakarta.persistence.LockModeType;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

	//List<Product> findByBusinessId(Long businessId);

	// Todos los productos de un negocio
    List<Product> findByCategory_Business_Id(Long businessId);
	
	List<Product> findByCategoryId(Long categoryId);

	//List<Product> findByBusinessIdAndActiveTrueAndStockGreaterThan(Long businessId, Integer stock);

	// Solo activos y con stock > X

	
	List<Product> findByActiveTrue();

	/**
	 * mientras una transacción usa esos productos, nadie más puede modificarlos
	 *
	 * <p>El predicado de negocio es obligatorio: el canal público de pedidos es
	 * anónimo y su única ancla de tenant es el slug de la URL, así que un id de
	 * producto ajeno no debe entrar al mapa de carga ni bajo lock pesimista.
	 *
	 * @param ids
	 * @param businessId
	 * @return
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Product p WHERE p.id IN :ids AND p.category.business.id = :businessId")
	List<Product> findAllByIdInForUpdate(@Param("ids") List<Long> ids, @Param("businessId") Long businessId);

	/**
	 * No hace SELECT del producto para validar stock, sino que actualizas el
	 * stock directamente en una sola query atómica.
	 * 
	 * Esto tiene ventajas enormes:
	 * 
	 * ✔ evita race conditions ✔ evita locks largos ✔ menos queries ✔ más escalable
	 * 
	 * @param productId
	 * @param quantity
	 * @return
	 */
	@Modifying
	@Query("""
			UPDATE Product p
			SET p.stock = p.stock - :quantity
			WHERE p.id = :productId
			AND p.category.business.id = :businessId
			AND p.stock >= :quantity
			""")
	int decrementStock(@Param("productId") Long productId, @Param("quantity") Integer quantity,
			@Param("businessId") Long businessId);
	
	@Modifying
	@Query("""
	    UPDATE Product p
	    SET p.stock = p.stock + :quantity
	    WHERE p.id = :productId
	    AND p.category.business.id = :businessId
	""")
	int incrementStock(
	        @Param("productId") Long productId,
	        @Param("quantity") Integer quantity,
	        @Param("businessId") Long businessId
	);

	List<Product> findByCategoryIdAndActiveTrue(Long categoriaId);
    
	//List<Product> findByActiveTrue();
	
	@Query("""
		       SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
		       FROM Product p
		       WHERE LOWER(TRIM(p.name)) = LOWER(TRIM(:name))
		       AND p.category.id = :categoryId
		       AND p.active = true
		       """)
		boolean existsByNameAndCategory(@Param("name") String name,
		                                @Param("categoryId") Long categoryId);
	
	// Solo activos y con stock > X
    List<Product> findByCategory_Business_IdAndActiveTrueAndStockGreaterThan(Long businessId, Integer stock);
}