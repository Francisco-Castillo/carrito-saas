package com.carrito.saas.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessUser;
import com.carrito.saas.repository.entity.User;

@Repository
public interface BusinessUserRepository extends JpaRepository<BusinessUser, Long> {

	//Optional<BusinessUser> findByUserUsername(String username);

	Optional<BusinessUser> findByUserAndBusiness(User user, Business business);
	Optional<BusinessUser> findFirstByUserUsername(String username);

	/**
	 * Todas las membresías de un usuario, para el login orgánico: sin slug
	 * explícito, el negocio se resuelve desde acá (una membresía → ese
	 * negocio, cero → 403, varias → 400 pidiendo el negocio).
	 */
	List<BusinessUser> findByUserUsername(String username);

	Optional<BusinessUser> findByUserUsernameAndBusinessSlug(String username, String slug);

	/**
	 * Membresías OWNER de todo el SaaS, para el listado de clientes del
	 * operador de plataforma (odd/tasks/plataforma-altas.md, T3): un owner
	 * por negocio; los negocios sin owner quedan afuera y el servicio los
	 * lista con dueño null.
	 */
	@Query("SELECT bu FROM BusinessUser bu JOIN bu.role r WHERE r.name = 'OWNER'")
	List<BusinessUser> findOwnerMemberships();

	/**
	 * Membresías de UN negocio, ordenadas por id de alta — el listado de
	 * usuarios del local para el operador (odd/tasks/plataforma-usuarios.md).
	 */
	List<BusinessUser> findByBusiness_IdOrderByIdAsc(Long businessId);
}
