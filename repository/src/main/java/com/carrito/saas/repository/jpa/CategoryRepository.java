package com.carrito.saas.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.Category;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * Todas las categorías del negocio en el orden del dueño.
     *
     * <p>El {@code ORDER BY} explícito es parte del contrato, no un detalle:
     * sin él PostgreSQL devuelve las filas en el orden del heap, que es el
     * orden de inserción hasta que alguien edita una fila — un UPDATE la
     * reescribe al final de la tabla. Eso es exactamente lo que hacía que la
     * carta pública mostrara las categorías en un orden distinto cada vez
     * (ver {@code PublicMenuCategoryOrderTests}).</p>
     *
     * <p>El desempate por id cubre los empates de {@code orden}: éstos existen
     * en la práctica porque el panel calcula el próximo valor como
     * {@code cantidad de filas + 1}, así que borrar una categoría intermedia
     * alcanza para repetir un número. Con el desempate, dos categorías
     * empatadas salen siempre en el mismo orden (la más vieja primero) y el
     * panel y la carta coinciden.</p>
     */
    @Query("""
            SELECT c
            FROM Category c
            WHERE c.business.id = :businessId
            ORDER BY c.orden ASC NULLS LAST, c.id ASC
            """)
    List<Category> findByBusinessIdOrdered(@Param("businessId") Long businessId);

    /**
     * Igual que {@link #findByBusinessIdOrdered} pero sólo las activas: es el
     * listado que sirve {@code GET /api/categorias} al panel, así que la tabla
     * de categorías y el selector del alta de productos y combos ven el mismo
     * orden que la carta.
     */
    @Query("""
            SELECT c
            FROM Category c
            WHERE c.business.id = :businessId
            AND c.active = true
            ORDER BY c.orden ASC NULLS LAST, c.id ASC
            """)
    List<Category> findActiveByBusinessIdOrdered(@Param("businessId") Long businessId);

    /**
     * Próximo lugar libre al final de la lista del negocio: el alta lo usa para
     * que una categoría nueva nazca última, sin depender de lo que pregunte el
     * panel. {@code MAX} ignora los nulos (una categoría creada sin orden no
     * participa del cálculo) y {@code COALESCE} cubre el negocio sin filas.
     */
    @Query("SELECT COALESCE(MAX(c.orden), 0) FROM Category c WHERE c.business.id = :businessId")
    Integer nextOrden(@Param("businessId") Long businessId);

    /**
     * Una sola categoría propia: los canales de escritura anclan el tenant
     * en el JWT, así que un id ajeno al negocio debe resolver como no
     * encontrado y jamás como un éxito silencioso.
     */
    Optional<Category> findByIdAndBusinessId(Long id, Long businessId);

    @Query("""
            SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END
            FROM Category c
            WHERE LOWER(TRIM(c.name)) = LOWER(TRIM(:name))
            AND c.business.id = :businessId
            """)
     boolean existsByNameIgnoreCaseAndBusiness(@Param("name") String name,
                                               @Param("businessId") Long businessId);

    /**
     * Igual que {@link #existsByNameIgnoreCaseAndBusiness} pero excluyendo la
     * propia categoría: la edición necesita detectar duplicados contra el
     * resto del negocio sin chocar con su nombre actual.
     */
    @Query("""
            SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END
            FROM Category c
            WHERE LOWER(TRIM(c.name)) = LOWER(TRIM(:name))
            AND c.business.id = :businessId
            AND c.id <> :id
            """)
     boolean existsByNameIgnoreCaseAndBusinessAndIdNot(@Param("name") String name,
                                                       @Param("businessId") Long businessId,
                                                       @Param("id") Long id);
    
    /**
     * {@code products.categoria_id} lleva una FK real: una categoría con
     * productos no puede borrarse (el delete nunca hace cascade hacia los
     * productos). Esta consulta permite fallar de forma explícita (409)
     * antes de chocar con la FK y terminar en un 500 genérico — misma forma
     * que {@code ProductRepository.existsInAnyCombo}.
     */
    @Query("""
            SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
            FROM Product p
            WHERE p.category.id = :categoryId
            """)
    boolean hasProducts(@Param("categoryId") Long categoryId);

}