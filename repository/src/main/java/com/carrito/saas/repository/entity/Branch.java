package com.carrito.saas.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Una sucursal de un negocio (multi-sucursal, odd/tasks/multi-sucursal.md,
 * S1/S3). El tenant sigue siendo {@link Business}: la frontera de seguridad
 * (JWT con businessId, {@code TenantContext}, scoping por tenant del JWT) no
 * cambia; la sucursal vive DEBAJO del negocio.
 *
 * <p><strong>Qué es por-sucursal hoy</strong> (fase 1: identidad y estado):
 * nombre, dirección y baja lógica. Los pedidos, horarios, WhatsApp y QR se
 * re-parentan a la sucursal en las fases siguientes (T3-T6 del doc).</p>
 *
 * <p>El nombre es UNIQUE dentro del negocio (no global): dos sucursales del
 * mismo negocio no pueden llamarse igual — el panel y el selector público
 * (S4) las distinguen por nombre — pero negocios distintos sí pueden
 * repetirlo ("Casa central" es legítimo en cada negocio).</p>
 *
 * <p>Los UNIQUE se declaran en la tabla con nombre explícito, igual que en
 * {@link Business}: un nombre generado por el entorno rompería migraciones
 * futuras que lo referencien.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "branches",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_branches_business_name", columnNames = {"business_id", "name"})})
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;

    /**
     * Nombre visible de la sucursal ("Casa central", "Sucursal Palermo").
     * Único dentro del negocio, ver el encabezado de la clase.
     */
    @Column(nullable = false)
    private String name;

    /** Dirección de la sucursal para el selector público (S4); opcional. */
    private String address;

    /**
     * Baja lógica (misma política que {@link Business#active}: reversible,
     * el historial no se toca, el borrado físico queda prohibido).
     * Las sucursales inactivas no aparecen en el selector público.
     */
    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

}
