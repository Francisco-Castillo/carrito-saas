package com.carrito.saas.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.BusinessDateException;

/**
 * Data access for {@link BusinessDateException}: el perfil y el menú público
 * leen las fechas ordenadas por fecha (y por posición dentro de cada fecha), y
 * el reemplazo del perfil borra la lista completa (delete-then-insert) dentro
 * de la transacción del servicio.
 */
@Repository
public interface BusinessDateExceptionRepository extends JpaRepository<BusinessDateException, Long> {

    List<BusinessDateException> findByBusiness_IdOrderByDateAscPositionAsc(Long businessId);

    void deleteByBusiness_Id(Long businessId);

}
