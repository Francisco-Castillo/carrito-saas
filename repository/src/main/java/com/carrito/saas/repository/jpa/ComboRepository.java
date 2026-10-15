package com.carrito.saas.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.carrito.saas.repository.entity.Combo;

public interface ComboRepository extends JpaRepository<Combo, Long> {
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

}
