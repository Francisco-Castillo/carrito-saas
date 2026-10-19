package com.carrito.saas.repository.entity;

import com.carrito.saas.repository.enums.QrTemplate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "business_settings")
@Getter
@Setter
public class BusinessSettings {
	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false, unique = true)
    private Business business;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_template", nullable = false, length = 300)
    private QrTemplate defaultTemplate;

    @Column(name = "show_logo", nullable = false)
    private Boolean showLogo;

    @Column(name = "show_url", nullable = false)
    private Boolean showUrl;
}
