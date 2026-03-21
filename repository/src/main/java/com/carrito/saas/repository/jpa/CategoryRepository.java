package com.carrito.saas.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.Category;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByBusinessId(Long businessId);
    
    @Query("""
            SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END
            FROM Category c
            WHERE LOWER(TRIM(c.name)) = LOWER(TRIM(:name))
            AND c.business.id = :businessId
            """)
     boolean existsByNameIgnoreCaseAndBusiness(@Param("name") String name,
                                               @Param("businessId") Long businessId);
    
		// Para frontend (clientes)
		List<Category> findByBusiness_IdAndActiveTrueOrderByOrdenAsc(Long businessId);

		// Para admin
		List<Category> findByBusiness_IdOrderByOrdenAsc(Long businessId);

}