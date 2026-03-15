package com.carrito.saas.repository.jpa;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.User;

@Repository
public interface UserRepository extends JpaRepository<User,Long> {
	
	 Optional<User> findByUsername(String username);

}
