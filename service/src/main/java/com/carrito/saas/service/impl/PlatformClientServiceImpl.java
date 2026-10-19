package com.carrito.saas.service.impl;

import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.CreatePlatformClientRequestDTO;
import com.carrito.saas.dto.PlatformClientDTO;
import com.carrito.saas.dto.UpdatePlatformClientRequestDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessUser;
import com.carrito.saas.repository.entity.Role;
import com.carrito.saas.repository.entity.User;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.service.interfaces.IPlatformClientService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * Platform client alta ({@code POST /api/platform/clients}):
 * odd/tasks/plataforma-altas.md S1/S4/S5.
 *
 * <p>One transaction creates the THREE rows a client needs to be real: the
 * {@link Business} (the locale, id drawn from {@code businesses_id_seq}),
 * the owner {@link User} with the bcrypt-hashed operator-typed password, and
 * the {@link BusinessUser} OWNER membership. Pre-checks answer the friendly
 * errors (409 duplicates, 400 malformed); the DB unique constraints stay as
 * the concurrency backstop — a lost race rethrows as the same 409, and the
 * transaction rollback leaves no half-registered client behind.</p>
 */
@Service
public class PlatformClientServiceImpl implements IPlatformClientService {

	@Override
	public List<PlatformClientDTO> listClients() {

		// Un owner por negocio (por diseño del alta); un negocio sin owner
		// aparece con campos de dueño en null — el operador ve la fila igual.
		Map<Long, BusinessUser> ownerByBusinessId = new HashMap<>();
		for (BusinessUser membership : businessUserRepository.findOwnerMemberships()) {
			ownerByBusinessId.put(membership.getBusiness().getId(), membership);
		}

		List<PlatformClientDTO> clients = new ArrayList<>();
		for (Business business : businessRepository.findAll(org.springframework.data.domain.Sort.by("id"))) {
			BusinessUser owner = ownerByBusinessId.get(business.getId());

			PlatformClientDTO dto = new PlatformClientDTO();
			dto.setBusinessId(business.getId());
			dto.setBusinessName(business.getName());
			dto.setSlug(business.getSlug());
			dto.setPhone(business.getPhone());
			dto.setOwnerUsername(owner == null ? null : owner.getUser().getUsername());
			dto.setOwnerEmail(owner == null ? null : owner.getUser().getEmail());
			clients.add(dto);
		}
		return clients;
	}

	@Override
	public PlatformClientDTO editClient(Long businessId, UpdatePlatformClientRequestDTO request) {

		if (request == null) {
			throw new BusinessException("No hay cambios para aplicar", ErrorType.VALIDATION);
		}

		Business business = businessRepository.findById(businessId)
				.orElseThrow(() -> new BusinessException("Cliente no encontrado", ErrorType.NOT_FOUND));

		// --- name: sólo si el payload lo manda (edición parcial) ----------
		if (request.getName() != null) {
			String name = blankToNull(request.getName());
			if (name == null) {
				throw new BusinessException("El nombre del local es obligatorio", ErrorType.VALIDATION);
			}
			business.setName(name);
		}

		// --- phone: sólo si el payload lo manda; blank limpia el campo ----
		if (request.getPhone() != null) {
			String phone = blankToNull(request.getPhone());
			if (phone != null && !PHONE_SHAPE.matcher(phone).matches()) {
				throw new BusinessException("El teléfono debe ser código de área y número, sin 0 ni 15",
						ErrorType.VALIDATION);
			}
			business.setPhone(phone);
		}

		// --- ownerEmail: sólo si el payload lo manda; sin auto-colisión ---
		if (request.getOwnerEmail() != null) {
			String email = blankToNull(request.getOwnerEmail());
			if (email == null) {
				throw new BusinessException("El email del dueño es obligatorio", ErrorType.VALIDATION);
			}
			User owner = ownerOf(businessId);
			if (owner == null) {
				throw new BusinessException("El local no tiene dueño: no hay email que editar", ErrorType.VALIDATION);
			}
			User collision = userRepository.findByEmail(email).orElse(null);
			if (collision != null && !collision.getId().equals(owner.getId())) {
				throw new BusinessException("Ya existe un usuario con ese email", ErrorType.CONFLICT);
			}
			owner.setEmail(email);
			userRepository.saveAndFlush(owner);
		}

		// --- active: reactivación/baja directa cuando el payload lo manda --
		if (request.getActive() != null) {
			business.setActive(request.getActive());
		}

		// El slug NUNCA se toca: es la identidad pública (QRs impresos,
		// URLs del menú) — el DTO de edición ni lo declara.
		businessRepository.saveAndFlush(business);

		return toDTO(business, ownerOf(businessId));
	}

	/** Owner membership del negocio, o null si no tiene (listado honesto). */
	private User ownerOf(Long businessId) {

		return businessUserRepository.findOwnerMemberships().stream()
				.filter(membership -> businessId.equals(membership.getBusiness().getId()))
				.map(BusinessUser::getUser)
				.findFirst()
				.orElse(null);
	}

	@Override
	public PlatformClientDTO deactivateClient(Long businessId) {

		Business business = businessRepository.findById(businessId)
				.orElseThrow(() -> new BusinessException("Cliente no encontrado", ErrorType.NOT_FOUND));

		// Baja LÓGICA (S3): la fila, el dueño y la membresía sobreviven con
		// active=false; el historial no se toca — el borrado físico está
		// prohibido. Idempotente: bajar un local ya de baja es un no-op 200.
		business.setActive(Boolean.FALSE);
		businessRepository.saveAndFlush(business);

		return toDTO(business, ownerOf(businessId));
	}

	private static final Pattern SLUG_SHAPE = Pattern.compile("^[a-z0-9-]+$");

	/** Mirrors businesses_phone_shape: digits, 8-14, leading 1-9. */
	private static final Pattern PHONE_SHAPE = Pattern.compile("^[1-9][0-9]{7,13}$");

	private final BusinessRepository businessRepository;
	private final UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final BusinessUserRepository businessUserRepository;
	private final PasswordEncoder passwordEncoder;

	public PlatformClientServiceImpl(BusinessRepository businessRepository, UserRepository userRepository,
			RoleRepository roleRepository, BusinessUserRepository businessUserRepository,
			PasswordEncoder passwordEncoder) {
		this.businessRepository = businessRepository;
		this.userRepository = userRepository;
		this.roleRepository = roleRepository;
		this.businessUserRepository = businessUserRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	@Transactional
	public PlatformClientDTO createClient(CreatePlatformClientRequestDTO request) {

		CreatePlatformClientRequestDTO.BusinessData businessData = request != null ? request.getBusiness() : null;
		CreatePlatformClientRequestDTO.OwnerData ownerData = request != null ? request.getOwner() : null;

		if (businessData == null || ownerData == null) {
			throw new BusinessException("El alta necesita el local y el dueño", ErrorType.VALIDATION);
		}

		// --- Business (the locale) --------------------------------------
		String name = blankToNull(businessData.getName());
		if (name == null) {
			throw new BusinessException("El nombre del local es obligatorio", ErrorType.VALIDATION);
		}

		String slug = blankToNull(businessData.getSlug());
		if (slug == null) {
			throw new BusinessException("El slug es obligatorio", ErrorType.VALIDATION);
		}
		if (!SLUG_SHAPE.matcher(slug).matches()) {
			throw new BusinessException("El slug sólo admite minúsculas, números y guiones", ErrorType.VALIDATION);
		}
		if (businessRepository.findBySlug(slug).isPresent()) {
			throw new BusinessException("Ya existe un local con ese slug", ErrorType.CONFLICT);
		}

		String phone = blankToNull(businessData.getPhone());
		if (phone != null && !PHONE_SHAPE.matcher(phone).matches()) {
			throw new BusinessException("El teléfono debe ser código de área y número, sin 0 ni 15",
					ErrorType.VALIDATION);
		}

		// --- Owner user --------------------------------------------------
		String username = blankToNull(ownerData.getUsername());
		if (username == null) {
			throw new BusinessException("El usuario del dueño es obligatorio", ErrorType.VALIDATION);
		}
		if (userRepository.findByUsername(username).isPresent()) {
			throw new BusinessException("Ya existe un usuario con ese nombre", ErrorType.CONFLICT);
		}

		String email = blankToNull(ownerData.getEmail());
		if (email == null) {
			throw new BusinessException("El email del dueño es obligatorio", ErrorType.VALIDATION);
		}
		if (userRepository.findByEmail(email).isPresent()) {
			throw new BusinessException("Ya existe un usuario con ese email", ErrorType.CONFLICT);
		}

		String password = blankToNull(ownerData.getPassword());
		if (password == null || password.length() < 8) {
			throw new BusinessException("La contraseña debe tener al menos 8 caracteres", ErrorType.VALIDATION);
		}

		// --- The three rows, one transaction ------------------------------
		try {
			Role ownerRole = resolveOwnerRole();

			Business business = new Business();
			business.setId(businessRepository.nextBusinessId());
			business.setName(name);
			business.setSlug(slug);
			business.setPhone(phone);
			businessRepository.saveAndFlush(business);

			User owner = new User();
			owner.setUsername(username);
			owner.setEmail(email);
			owner.setPassword(passwordEncoder.encode(password));
			owner.setRoles(java.util.Set.of(ownerRole));
			userRepository.saveAndFlush(owner);

			BusinessUser membership = new BusinessUser();
			membership.setUser(owner);
			membership.setBusiness(business);
			membership.setRole(ownerRole);
			businessUserRepository.saveAndFlush(membership);

			return toDTO(business, owner);
		} catch (DataIntegrityViolationException race) {
			// Concurrency backstop: two simultaneous altas racing for the same
			// slug/username/email. Same legible contract as the pre-checks.
			throw new BusinessException("Ya existe un local o usuario con esos datos", ErrorType.CONFLICT);
		}
	}

	/**
	 * OWNER authority, find-or-create: the row is reused when it already
	 * exists (uq_roles_name rejects a duplicate) and seeded on a fresh DB.
	 * Managed either way — Hibernate needs it for user_roles.
	 */
	private Role resolveOwnerRole() {

		return roleRepository.findByName("OWNER").orElseGet(this::createOwnerRole);
	}

	private Role createOwnerRole() {

		Role created = new Role();
		created.setName("OWNER");
		return roleRepository.saveAndFlush(created);
	}

	private PlatformClientDTO toDTO(Business business, User owner) {

		PlatformClientDTO dto = new PlatformClientDTO();
		dto.setBusinessId(business.getId());
		dto.setBusinessName(business.getName());
		dto.setSlug(business.getSlug());
		dto.setPhone(business.getPhone());
		dto.setOwnerUsername(owner == null ? null : owner.getUsername());
		dto.setOwnerEmail(owner == null ? null : owner.getEmail());
		dto.setActive(business.getActive());
		return dto;
	}

	private String blankToNull(String value) {

		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
