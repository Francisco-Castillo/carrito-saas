package com.carrito.saas.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.BusinessHours;

/**
 * Data access for {@link BusinessHours}: the public menu reads the days
 * ordered Monday→Sunday, and the profile update path replaces the whole list
 * (delete-then-insert) inside the service transaction.
 *
 * <p>Multi-sucursal (odd/tasks/multi-sucursal.md, S3/T4):
 * {@link #findByBusinessIdAndOptionalBranch} es la query de lectura con
 * sucursal opcional — con {@code branchId} trae los turnos de ESA sucursal
 * más los legacy a nivel negocio (branch NULL), que la ven todas las
 * sucursales mientras no definan los suyos; con NULL es la vista negocio
 * entera (negocio sin sucursales). Los finders derivados legacy quedan para
 * el menú/compatibilidad.</p>
 */
@Repository
public interface BusinessHoursRepository extends JpaRepository<BusinessHours, Long> {

    List<BusinessHours> findByBusiness_IdOrderByDayOfWeekAscPositionAsc(Long businessId);

    @Query("""
            SELECT h FROM BusinessHours h
            WHERE (:branchId IS NULL OR h.branch.id = :branchId OR h.branch IS NULL)
              AND h.business.id = :businessId
            ORDER BY h.dayOfWeek ASC, h.position ASC
            """)
    List<BusinessHours> findByBusinessIdAndOptionalBranch(@Param("businessId") Long businessId,
            @Param("branchId") Long branchId);

    void deleteByBusiness_Id(Long businessId);

    /**
     * Reemplazo en bloque con sucursal opcional: con {@code branchId} borra
     * SÓLO los turnos de esa sucursal (los legacy a nivel negocio quedan, los
     * conservan las demás sucursales); con NULL borra el negocio entero
     * (comportamiento legacy).
     */
    @Modifying
    @Query("""
            DELETE FROM BusinessHours h
            WHERE h.business.id = :businessId
              AND (:branchId IS NULL OR h.branch.id = :branchId)
            """)
    void deleteByBusinessIdAndOptionalBranch(@Param("businessId") Long businessId, @Param("branchId") Long branchId);

}
