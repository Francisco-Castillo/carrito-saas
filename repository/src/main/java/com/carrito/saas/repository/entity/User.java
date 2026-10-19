package com.carrito.saas.repository.entity;

import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.JoinColumn;
import lombok.Data;

@Entity
@Table(name = "users")
@Data
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String username;

	private String password;
	
	@Column(unique = true)
	private String email;

	/**
	 * Baja lógica del usuario (odd/tasks/plataforma-usuarios.md, S4):
	 * {@code false} = no puede loguearse. El CustomUserDetailsService lo
	 * marca disabled — el error es indistinguible de una clave incorrecta
	 * (no revela el estado de la cuenta). Historial intacto; el borrado
	 * físico está prohibido. Igual patrón que businesses.active.
	 */
	@Column(nullable = false)
	private Boolean active = Boolean.TRUE;
	
	@ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles;

}
