package com.carrito.saas.repository.jpa;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

	// Todos los productos de un negocio
    List<Product> findByCategory_Business_Id(Long businessId);

	/**
	 * Un solo producto propio: el canal de administración ancla el tenant en
	 * el JWT, así que un id ajeno debe resolver como no encontrado y jamás
	 * como un éxito silencioso. {@link Product} no tiene business propio: el
	 * ancla es {@code category.business.id}.
	 */
	Optional<Product> findByIdAndCategory_Business_Id(Long id, Long businessId);

	/**
	 * Conjunto de productos propios: la creación de combos resuelve los ids
	 * referenciados con este finder acotado al negocio del JWT, así que un id
	 * ajeno o inexistente simplemente no aparece en el resultado (el servicio
	 * lo convierte en 404 nombrando el id ofensor).
	 */
	List<Product> findAllByIdInAndCategory_Business_Id(Collection<Long> ids, Long businessId);

	/**
	 * {@code combo_products.producto_id} lleva una FK real (RESTRICT), así
	 * que un producto usado por cualquier combo no puede borrarse: esta
	 * consulta permite fallar de forma explícita (409) antes de chocar con la
	 * FK y terminar en un 500 genérico.
	 */
	@Query("""
			SELECT CASE WHEN COUNT(cp) > 0 THEN true ELSE false END
			FROM ComboProduct cp
			WHERE cp.product.id = :productId
			""")
	boolean existsInAnyCombo(@Param("productId") Long productId);

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
			AND (p.stock IS NULL OR p.stock >= :quantity)
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

	@Query("""
		       SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
		       FROM Product p
		       WHERE LOWER(TRIM(p.name)) = LOWER(TRIM(:name))
		       AND p.category.id = :categoryId
		       AND p.active = true
		       """)
		boolean existsByNameAndCategory(@Param("name") String name,
		                                @Param("categoryId") Long categoryId);
	
	/**
	 * Public menu read path. Follows the documented {@code Product.stock}
	 * convention: {@code null} is infinite stock and must be offered,
	 * {@code 0} is no stock and must stay filtered out, {@code > 0} is
	 * limited stock. In SQL {@code NULL > 0} is not true, so the null case
	 * has to be spelled out explicitly.
	 *
	 * <p>El {@code ORDER BY} es parte del contrato con la carta: el frontend
	 * agrupa los productos <em>en el orden en que llegan</em>
	 * ({@code groupByCategory} de {@code Menu.tsx}), así que este orden ES el
	 * orden de las categorías en la carta. Primero el orden del dueño, después
	 * el id de la categoría como desempate y por último el id del producto
	 * (el orden de carga dentro de la categoría). Sin esto la carta heredaba
	 * el orden del heap y cualquier edición de un producto movía su categoría
	 * (ver {@code PublicMenuCategoryOrderTests}).</p>
	 *
	 * <p>{@code p.category.active = true} es la otra mitad de la visibilidad:
	 * apagar una categoría en el panel tiene que sacarla de la carta, y sus
	 * productos con ella. Sin este predicado el switch no cambiaba nada de lo
	 * que ve el cliente.
	 * (ver {@code InactiveCategoryNotOfferedTests}).</p>
	 */
	@Query("""
			SELECT p
			FROM Product p
			WHERE p.category.business.id = :businessId
			AND p.active = true
			AND p.category.active = true
			AND (p.stock IS NULL OR p.stock > 0)
			ORDER BY p.category.orden ASC NULLS LAST, p.category.id ASC, p.id ASC
			""")
    List<Product> findPublicMenuProducts(@Param("businessId") Long businessId);
}