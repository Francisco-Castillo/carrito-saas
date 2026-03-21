package com.carrito.saas.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.CancellationReason;

@Repository
public interface CancellationReasonRepository extends JpaRepository<CancellationReason, Long> {
	List<CancellationReason> findByActiveTrue();
}
