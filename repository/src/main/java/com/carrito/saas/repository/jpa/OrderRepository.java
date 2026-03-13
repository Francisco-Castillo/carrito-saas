package com.carrito.saas.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.Order;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

	@Query("""
			SELECT COALESCE(MAX(o.orderNumber),0)
			FROM Order o
			WHERE o.businessId = :businessId
			""")
	Integer findMaxOrderNumberByBusiness(Long businessId);

	@Query("""
			SELECT o
			FROM Order o
			LEFT JOIN FETCH o.items
			WHERE o.businessId = :businessId
			AND o.status <> 'DELIVERED'
			ORDER BY o.createdAt ASC
			""")
	List<Order> findActiveOrders(Long businessId);

}
