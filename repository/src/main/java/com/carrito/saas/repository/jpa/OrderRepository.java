package com.carrito.saas.repository.jpa;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
			AND o.status NOT IN ('DELIVERED','CANCELLED')
			ORDER BY o.createdAt ASC
			""")
	List<Order> findActiveOrders(Long businessId);

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
			SELECT AVG(TIMESTAMPDIFF(SECOND,o.preparingAt,o.readyAt))
			FROM Order o
			WHERE o.business.id = :restaurantId
			AND DATE(o.createdAt) = :today
			AND o.readyAt IS NOT NULL
			""")
	Long avgPrepTimeToday(Long restaurantId, LocalDate today);

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

}
