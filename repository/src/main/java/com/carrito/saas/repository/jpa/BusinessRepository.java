package com.carrito.saas.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.Business;

/**
 * Data access for {@link Business}: lookup by public {@code slug} and id
 * listing for batch jobs.
 */
@Repository
public interface BusinessRepository extends JpaRepository<Business, Long> {

    Optional<Business> findBySlug(String slug);

    @Query("SELECT b.id FROM Business b")
    List<Long> findAllIds();
}
