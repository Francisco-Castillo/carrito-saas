package com.carrito.saas.repository.entity;

import java.time.LocalTime;

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
 * One row per weekday of a business's opening hours ({@code dayOfWeek}
 * 1 = Monday … 7 = Sunday, ISO convention). Owned by the storefront profile:
 * the owner replaces the whole list via {@code PUT /api/business/profile}, so
 * the UNIQUE (business_id, day_of_week) constraint — declared on the table
 * with an explicit name, same policy as {@link Business}'s constraints —
 * guarantees at most one franja per day.
 *
 * <p>When {@code closed} is {@code true} the times are NULL (the day is not
 * open, there is nothing to show); when it is {@code false} both
 * {@code opensAt} and {@code closesAt} are present and ordered
 * ({@code opensAt < closesAt}) — that shape is enforced in
 * {@code BusinessProfileServiceImpl} before save, not by the schema.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "business_hours",
        uniqueConstraints = @UniqueConstraint(name = "uq_business_hours_business_day",
                columnNames = {"business_id", "day_of_week"}))
public class BusinessHours {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;

    /** ISO day: 1 = Monday … 7 = Sunday. */
    @Column(name = "day_of_week", nullable = false)
    private Integer dayOfWeek;

    /** Present only when {@link #closed} is {@code false}. */
    private LocalTime opensAt;

    private LocalTime closesAt;

    @Column(nullable = false)
    private boolean closed;

}
