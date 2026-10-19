package com.carrito.saas.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.TableRequest;
import com.carrito.saas.repository.enums.RequestStatus;

@Repository
public interface TableRequestRepository extends JpaRepository<TableRequest, Long> {

	List<TableRequest> findByBusinessIdAndStatus(Long restaurantId, RequestStatus status);

}
