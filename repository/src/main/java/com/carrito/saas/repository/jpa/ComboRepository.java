package com.carrito.saas.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.carrito.saas.repository.entity.Combo;

public interface ComboRepository extends JpaRepository<Combo, Long> {
	/**
	 * Lectura del panel administrativo (GET /api/combos): devuelve TODOS los
	 * combos del negocio, activos e inactivos — el panel necesita ver los
	 * apagados para poder volver a encenderlos.
	 */
	@Query("""
			    SELECT DISTINCT c
			    FROM Combo c
			    JOIN FETCH c.items cp
			    JOIN FETCH cp.product
			    JOIN c.category cat
			    WHERE cat.business.id = :businessId
			""")
	List<Combo> findFullMenuCombos(Long businessId);

	/**
	 * Lectura de la carta pública (GET /api/menu/{slug}): igual que
	 * {@code findFullMenuCombos} pero sólo combos ACTIVOS — el switch
	 * on/off del panel debe poder sacar un combo de la carta. Mismo
	 * precedente que {@code ProductRepository.findPublicMenuProducts}:
	 * la regla vive en la consulta, no en el servicio.
	 */
	@Query("""
			    SELECT DISTINCT c
			    FROM Combo c
			    JOIN FETCH c.items cp
			    JOIN FETCH cp.product
			    JOIN c.category cat
			    WHERE cat.business.id = :businessId
			    AND c.active = true
			""")
	List<Combo> findActiveFullMenuCombos(Long businessId);

	/**
	 * Finder acotado al negocio para la actualización: trae el combo con sus
	 * items y productos en una sola consulta y filtra por la categoría, que
	 * es donde vive el businessId. Un id ajeno o inexistente resuelve vacío
	 * — el servicio lo mapea a 404.
	 */
	@Query("""
			    SELECT DISTINCT c
			    FROM Combo c
			    JOIN FETCH c.items cp
			    JOIN FETCH cp.product
			    JOIN c.category cat
			    WHERE c.id = :id
			    AND cat.business.id = :businessId
			""")
	Optional<Combo> findByIdAndBusinessId(@Param("id") Long id, @Param("businessId") Long businessId);

	/**
	 * Finder de duplicados acotado al negocio (el nombre vive en el combo, el
	 * negocio en su categoría): misma forma que
	 * {@code CategoryRepository.existsByNameIgnoreCaseAndBusiness}. La ruta
	 * de creación lo usa para rechazar un nombre repetido dentro del MISMO
	 * negocio con 409; el mismo nombre en otro negocio es otro tenant y debe
	 * seguir creándose.
	 */
	@Query("""
			    SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END
			    FROM Combo c
			    WHERE LOWER(TRIM(c.name)) = LOWER(TRIM(:name))
			    AND c.category.business.id = :businessId
			""")
	boolean existsByNameIgnoreCaseAndBusiness(@Param("name") String name,
			@Param("businessId") Long businessId);

	/**
	 * Esta consulta trae los comboProducts (items) y por cada item sus Productos, todo en una sola consulta.
	 * @param ids
	 * @param businessId
	 * @return
	 */
	@Query("""
			    SELECT DISTINCT c
			    FROM Combo c
			    JOIN FETCH c.items cp
			    JOIN FETCH cp.product
			    JOIN c.category cat
			    WHERE c.id IN :ids
			    AND cat.business.id = :businessId
			""")
	List<Combo> findFullMenuCombosByIds(@Param("ids") List<Long> ids, @Param("businessId") Long businessId);

	/**
	 * {@code order_items.combo_id} lleva una FK real (a diferencia de
	 * {@code order_items.product_id}, que no la tiene): un combo referenciado
	 * por un pedido no puede borrarse. Esta consulta permite fallar de forma
	 * explícita (409) antes de chocar con la FK y terminar en un 500 genérico
	 * — misma forma que {@code ProductRepository.existsInAnyCombo}. La
	 * consulta vive aquí porque OrderItemRepository no es superficie de
	 * escritura de esta unidad y JPQL puede cruzar entidades.
	 */
	@Query("""
			    SELECT CASE WHEN COUNT(oi) > 0 THEN true ELSE false END
			    FROM OrderItem oi
			    WHERE oi.combo.id = :comboId
			""")
	boolean existsOrderReferencing(@Param("comboId") Long comboId);

}
