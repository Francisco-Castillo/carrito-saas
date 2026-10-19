package com.carrito.saas.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.RestaurantTable;

@Repository
public interface TableRepository extends JpaRepository<RestaurantTable, Long> {

	Optional<RestaurantTable> findByQrToken(String token);

    List<RestaurantTable> findByBusinessId(Long businessId);

    Optional<RestaurantTable> findByIdAndBusinessId(
            Long id,
            Long businessId);

    boolean existsByBusinessIdAndTableNumber(
            Long businessId,
            Integer tableNumber);

    boolean existsByBusinessIdAndTableNumberAndIdNot(
            Long businessId,
            Integer tableNumber,
            Long id);

    Page<RestaurantTable> findByBusinessId(
            Long businessId,
            Pageable pageable);

}
