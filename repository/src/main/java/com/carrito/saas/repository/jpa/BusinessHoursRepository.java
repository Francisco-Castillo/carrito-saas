package com.carrito.saas.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.BusinessHours;

/**
 * Data access for {@link BusinessHours}: the public menu reads the days
 * ordered Monday→Sunday, and the profile update path replaces the whole list
 * (delete-then-insert) inside the service transaction.
 */
@Repository
public interface BusinessHoursRepository extends JpaRepository<BusinessHours, Long> {

    List<BusinessHours> findByBusiness_IdOrderByDayOfWeekAsc(Long businessId);

    void deleteByBusiness_Id(Long businessId);

}
