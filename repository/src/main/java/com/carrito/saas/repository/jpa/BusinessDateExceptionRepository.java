package com.carrito.saas.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.BusinessDateException;

/**
 * Data access for {@link BusinessDateException}: el perfil y el menú público
 * leen las fechas ordenadas por fecha (y por posición dentro de cada fecha), y
 * el reemplazo del perfil borra la lista completa (delete-then-insert) dentro
 * de la transacción del servicio.
 *
 * <p>Multi-sucursal (odd/tasks/multi-sucursal.md, S3/T4):
 * {@link #findByBusinessIdAndOptionalBranch} es la query de lectura con
 * sucursal opcional — con {@code branchId} trae las excepciones de ESA
 * sucursal más las legacy a nivel negocio (branch NULL), que la ven todas
 * las sucursales mientras no definan las suyas; con NULL es la vista negocio
 * entera. Los finders derivados legacy quedan para el menú/compatibilidad.</p>
 */
@Repository
public interface BusinessDateExceptionRepository extends JpaRepository<BusinessDateException, Long> {

    List<BusinessDateException> findByBusiness_IdOrderByDateAscPositionAsc(Long businessId);

    @Query("""
            SELECT e FROM BusinessDateException e
            WHERE (:branchId IS NULL OR e.branch.id = :branchId OR e.branch IS NULL)
              AND e.business.id = :businessId
            ORDER BY e.date ASC, e.position ASC
            """)
    List<BusinessDateException> findByBusinessIdAndOptionalBranch(@Param("businessId") Long businessId,
            @Param("branchId") Long branchId);

    void deleteByBusiness_Id(Long businessId);

    /**
     * Reemplazo en bloque con sucursal opcional: con {@code branchId} borra
     * SÓLO las excepciones de esa sucursal (las legacy a nivel negocio quedan,
     * las conservan las demás sucursales); con NULL borra el negocio entero
     * (comportamiento legacy).
     */
    @Modifying
    @Query("""
            DELETE FROM BusinessDateException e
            WHERE e.business.id = :businessId
              AND (:branchId IS NULL OR e.branch.id = :branchId)
            """)
    void deleteByBusinessIdAndOptionalBranch(@Param("businessId") Long businessId, @Param("branchId") Long branchId);

}
