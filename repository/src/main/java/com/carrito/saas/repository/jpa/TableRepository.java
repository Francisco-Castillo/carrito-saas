package com.carrito.saas.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.TableStatus;

@Repository
public interface TableRepository extends JpaRepository<RestaurantTable, Long> {

	Optional<RestaurantTable> findByQrToken(String token);

    List<RestaurantTable> findByBusinessId(Long businessId);

    Optional<RestaurantTable> findByIdAndBusinessId(
            Long id,
            Long businessId);

    /** Número ocupado por una mesa VIVA (una dada de baja libera su número). */
    boolean existsByBusinessIdAndTableNumberAndStatusNot(
            Long businessId,
            Integer tableNumber,
            TableStatus status);

    boolean existsByBusinessIdAndTableNumberAndIdNotAndStatusNot(
            Long businessId,
            Integer tableNumber,
            Long id,
            TableStatus status);

    Page<RestaurantTable> findByBusinessId(
            Long businessId,
            Pageable pageable);

}
