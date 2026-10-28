package com.carrito.saas.repository.jpa;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.enums.OrderStatus;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

	@Query("""
			SELECT COALESCE(MAX(o.orderNumber),0)
			FROM Order o
			WHERE o.business.id = :businessId
			""")
	Integer findMaxOrderNumberByBusiness(Long businessId);

	@Query("""
			SELECT DISTINCT o
			FROM Order o
			LEFT JOIN FETCH o.items
			WHERE o.business.id = :businessId
			AND o.status NOT IN ('DELIVERED','CANCELLED','PENDING_PAYMENT')
			ORDER BY o.createdAt ASC
			""")
	List<Order> findActiveOrders(Long businessId);

	/**
	 * Pedidos del negocio en una ventana de un día, TODOS los estados
	 * (incluidos DELIVERED y CANCELLED, que findActiveOrders excluye):
	 * es la fuente de la vista de gestión del mostrador.
	 *
	 * <p>Sin filtro de estado a propósito — la consulta del KDS no se toca.
	 * La ventana es [start, end). Items, mesa y motivo de cancelación van
	 * fetch-joined para mapear fuera de la sesión sin lazy loading.</p>
	 */
	@Query("""
			SELECT DISTINCT o
			FROM Order o
			LEFT JOIN FETCH o.items
			LEFT JOIN FETCH o.restaurantTable
			LEFT JOIN FETCH o.cancellationReason
			WHERE o.business.id = :businessId
			AND o.createdAt >= :start
			AND o.createdAt < :end
			ORDER BY o.createdAt ASC
			""")
	List<Order> findOrdersByDay(Long businessId, LocalDateTime start, LocalDateTime end);

	/**
	 * Contar pedidos de hoy
	 * 
	 * @param restaurantId
	 * @param today
	 * @return
	 */
	@Query("""
			SELECT COUNT(o)
			FROM Order o
			WHERE o.business.id = :restaurantId
			AND DATE(o.createdAt) = :today
			""")
	long countOrdersToday(Long restaurantId, LocalDate today);

	/**
	 * Ingresos del dia
	 * 
	 * @param restaurantId
	 * @param today
	 * @return
	 */
	@Query("""
			SELECT COALESCE(SUM(o.total),0)
			FROM Order o
			WHERE o.business.id = :restaurantId
			AND DATE(o.createdAt) = :today
			AND o.status IN :statuses
			""")
	double sumRevenueToday(Long restaurantId, LocalDate today, List<OrderStatus> statuses);

	/**
	 * Tiempo promedio de preparacion
	 * 
	 * @param restaurantId
	 * @param today
	 * @return
	 */
	@Query("""
		    SELECT COALESCE(AVG(TIMESTAMPDIFF(SECOND,o.preparingAt,o.readyAt)), 0)
		    FROM Order o
		    WHERE o.business.id = :restaurantId
		    AND DATE(o.createdAt) = :today
		    AND o.readyAt IS NOT NULL
		""")
		Double avgPrepTimeToday(Long restaurantId, LocalDate today);

	/**
	 * Conteo por estado
	 * 
	 * @param restaurantId
	 * @param status
	 * @return
	 */
	@Query("""
			SELECT o.status, COUNT(o)
			FROM Order o
			WHERE o.business.id = :restaurantId
			GROUP BY o.status
			""")
	List<Object[]> countOrdersByStatus(Long restaurantId);

	/**
	 * Top productos
	 * 
	 * @param restaurantId
	 * @return
	 */
	@Query("""
			SELECT oi.productName, SUM(oi.quantity)
			FROM OrderItem oi
			JOIN oi.order o
			WHERE o.business.id = :restaurantId
			AND o.createdAt >= :start
			AND o.createdAt < :end
			GROUP BY oi.productName
			ORDER BY SUM(oi.quantity) DESC
			""")
	List<Object[]> findTopProductsToday(Long restaurantId, LocalDateTime start, LocalDateTime end);

	/**
	 * Ventas por hora
	 * 
	 * @param restaurantId
	 * @return
	 */
	@Query("""
			SELECT HOUR(o.createdAt), SUM(o.total)
			FROM Order o
			WHERE o.business.id = :restaurantId
			AND o.createdAt >= :start
			AND o.createdAt < :end
			GROUP BY HOUR(o.createdAt)
			ORDER BY HOUR(o.createdAt)
			""")
	List<Object[]> findSalesByHourToday(Long restaurantId, LocalDateTime start, LocalDateTime end);

	/**
	 * Ventas por día: total facturado agrupado por día calendario en una
	 * ventana [start, end). Misma semántica que findSalesByHourToday:
	 * SUM(o.total) sin filtro de estado, para que el gráfico de 7 días
	 * cuente lo mismo que el de hoy.
	 */
	@Query("""
			SELECT CAST(o.createdAt AS LocalDate), SUM(o.total)
			FROM Order o
			WHERE o.business.id = :restaurantId
			AND o.createdAt >= :start
			AND o.createdAt < :end
			GROUP BY CAST(o.createdAt AS LocalDate)
			ORDER BY CAST(o.createdAt AS LocalDate)
			""")
	List<Object[]> findSalesByDay(Long restaurantId, LocalDateTime start, LocalDateTime end);
	
	Optional<Order> findByIdAndBusinessId(Long id, Long businessId);

}
