package com.carrito.saas.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
