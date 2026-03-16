package com.carrito.saas.service.impl;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.carrito.saas.dto.CreateUserRequestDTO;
import com.carrito.saas.dto.UserLoginInfoDTO;
import com.carrito.saas.dto.UserResponseDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessUser;
import com.carrito.saas.repository.entity.Role;
import com.carrito.saas.repository.entity.User;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.service.interfaces.IUserService;

@Service
public class UserServiceImpl implements IUserService {

	private final UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final BusinessUserRepository businessUserRepository;
	private final BusinessRepository businessRepository;
	private final PasswordEncoder passwordEncoder;

	public UserServiceImpl(UserRepository userRepository, RoleRepository roleRepository,
			BusinessUserRepository businessUserRepository, BusinessRepository businessRepository,
			PasswordEncoder passwordEncoder) {
		super();
		this.userRepository = userRepository;
		this.roleRepository = roleRepository;
		this.businessUserRepository = businessUserRepository;
		this.businessRepository = businessRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	public UserResponseDTO createUser(String restaurantSlug, CreateUserRequestDTO request) {
		 if(userRepository.findByUsername(request.getUsername()).isPresent()){
	            throw new RuntimeException("Username already exists");
	        }

	        Business restaurant = businessRepository
	                .findBySlug(restaurantSlug)
	                .orElseThrow(() -> new RuntimeException("Restaurant not found"));

	        Role role = roleRepository
	                .findByName(request.getRole())
	                .orElseThrow(() -> new RuntimeException("Role not found"));

	        User user = new User();
	        user.setUsername(request.getUsername());
	        user.setPassword(passwordEncoder.encode(request.getPassword()));

	        userRepository.save(user);

	        BusinessUser ru = new BusinessUser();
	        ru.setUser(user);
	        ru.setBusiness(restaurant);
	        ru.setRole(role);

	        businessUserRepository.save(ru);

	        return new UserResponseDTO(
	                user.getId(),
	                user.getUsername(),
	                role.getName()
	        );
	    }
	
	public UserLoginInfoDTO getLoginInfo(String username){

	    BusinessUser ru = businessUserRepository
	            .findByUserUsername(username)
	            .orElseThrow();

	    return new UserLoginInfoDTO(
	            ru.getRole().getName(),
	            ru.getBusiness().getSlug()
	    );
	}

}
