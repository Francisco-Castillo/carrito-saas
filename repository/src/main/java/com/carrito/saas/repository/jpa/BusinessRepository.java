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

    /**
     * Next id for platform-created businesses (docs/migrations/
     * 2026-10-19-businesses-id-sequence.sql). The entity has NO generator:
     * its rows were born from seeds and migrations with manual ids, so
     * ddl-auto never put a default on the column. The sequence starts far
     * above the manual seed ids (990000001) and CONVIVES with them — manual
     * inserts keep working; only this alta draws from the sequence.
     */
    @Query(value = "SELECT nextval('businesses_id_seq')", nativeQuery = true)
    Long nextBusinessId();
}
