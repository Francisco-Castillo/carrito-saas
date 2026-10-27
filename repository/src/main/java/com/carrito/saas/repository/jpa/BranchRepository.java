package com.carrito.saas.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.Branch;

@Repository
public interface BranchRepository extends JpaRepository<Branch, Long> {

    /** Sucursales del negocio, ordenadas por id de alta (panel del dueño). */
    List<Branch> findByBusinessIdOrderByIdAsc(Long businessId);

    /**
     * Una sola sucursal propia: los canales de escritura anclan el tenant en
     * el JWT, así que un id ajeno al negocio resuelve como no encontrado y
     * jamás como un éxito silencioso (mismo contrato que
     * {@code CategoryRepository#findByIdAndBusinessId}).
     */
    Optional<Branch> findByIdAndBusinessId(Long id, Long businessId);

    boolean existsByBusinessIdAndNameIgnoreCase(Long businessId, String name);

    /** Igual que arriba, excluyendo la propia fila (edición). */
    boolean existsByBusinessIdAndNameIgnoreCaseAndIdNot(Long businessId, String name, Long id);

    long countByBusinessId(Long businessId);

    /** Sucursales activas del negocio, ordenadas por alta (fallback y default del checkout). */
    List<Branch> findByBusinessIdAndActiveTrueOrderByIdAsc(Long businessId);

    /** Primera sucursal activa (la "default"): destino del pedido sin branchId. */
    Branch findFirstByBusinessIdAndActiveTrueOrderByIdAsc(Long businessId);

}
