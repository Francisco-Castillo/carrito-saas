package com.carrito.saas.repository.jpa;

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
	
	Optional<BusinessUser> findByUserUsernameAndBusinessSlug(String username, String slug);
}
