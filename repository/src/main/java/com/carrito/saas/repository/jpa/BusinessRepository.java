package com.carrito.saas.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.Business;

import jakarta.persistence.LockModeType;

/**
 * Data access for {@link Business}: lookup by public {@code slug} and id
 * listing for batch jobs.
 */
@Repository
public interface BusinessRepository extends JpaRepository<Business, Long> {

    Optional<Business> findBySlug(String slug);

    /**
     * El negocio tomado para escritura, para el alta de categorías.
     *
     * <p>El alta calcula su lugar con {@code max(orden) + 1}: leer el máximo y
     * después insertar es un read-then-write, así que dos altas concurrentes
     * del mismo negocio leían el mismo máximo y empataban (la segunda no
     * quedaba última). Tomar esta fila serializa las altas del negocio sin
     * migraciones ni constraints nuevos: la transacción del alta espera a que
     * la anterior libere y recién ahí calcula su lugar.</p>
     *
     * <p>El lock se toma sobre la fila del negocio y no sobre sus categorías
     * porque el negocio existe siempre (una fila conocida) y su alcance es
     * exactamente el que hay que serializar.</p>
     *
     * <p>Hibernate renderiza {@code PESSIMISTIC_WRITE} en PostgreSQL como
     * {@code FOR NO KEY UPDATE} ({@code PostgreSQLDialect#getForUpdateString}),
     * no como {@code FOR UPDATE}: alcanza igual, porque ese modo también se
     * bloquea consigo mismo y con {@code FOR SHARE}, y no bloquea el
     * {@code FOR KEY SHARE} que los INSERT de pedidos toman sobre la misma
     * fila.</p>
     *
     * <p>Ver {@code CategoryCreateSerializationTests}: el test sostiene la fila
     * desde otra conexión con {@code FOR NO KEY UPDATE} —compatible con el
     * {@code FOR KEY SHARE} que el INSERT del alta ya toma por la FK, para no
     * medir el lock de la FK— y exige que el alta espere.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Business b WHERE b.id = :id")
    Optional<Business> findByIdForUpdate(@Param("id") Long id);

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
