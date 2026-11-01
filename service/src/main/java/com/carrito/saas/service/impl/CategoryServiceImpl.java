package com.carrito.saas.service.impl;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.carrito.saas.dto.CategoryDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.security.SecurityService;
import com.carrito.saas.service.interfaces.ICategoryService;
import com.carrito.saas.service.mapper.interfaces.ICategoryMapper;

@Service
public class CategoryServiceImpl implements ICategoryService {

	private final CategoryRepository categoryRepository;
	private final BusinessRepository businessRepository;
	private final ICategoryMapper iCategoryMapper;
	private final SecurityService securityService;

	public CategoryServiceImpl(CategoryRepository categoryRepository, BusinessRepository businessRepository,
			ICategoryMapper iCategoryMapper, SecurityService securityService) {
		this.categoryRepository = categoryRepository;
		this.businessRepository = businessRepository;
		this.iCategoryMapper = iCategoryMapper;
		this.securityService = securityService;
	}

	@Override
	@Transactional
	public CategoryDTO crearCategoria(CategoryDTO dto) {

		Long businessId = securityService.getCurrentBusinessId();
		
	
		if (dto.getName() == null || dto.getName().isBlank())
			throw new BusinessException("El nombre de la categoría es obligatorio", ErrorType.VALIDATION);

		// El negocio se toma para escritura: el lugar de la categoría sale de
		// max(orden) + 1, un read-then-write que tiene que estar serializado por
		// negocio (ver lockCurrentBusiness).
		Business business = lockCurrentBusiness();

		String normalizedName = dto.getName().trim();

		// Validamos existencia
		boolean exists = categoryRepository.existsByNameIgnoreCaseAndBusiness(normalizedName, business.getId());

		if (exists) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Ya existe una categoría con ese nombre para este negocio");
		}

		Category c = new Category();
		c.setName(normalizedName);
		// El servidor es dueño del lugar de la categoría en la lista: el alta
		// nace al final (max(orden) + 1) y el order que manda el cliente se
		// ignora — aceptarlo producía empates (el panel calculaba filas + 1, que
		// repite un número si se borró una categoría intermedia) y la nueva
		// quedaba en el medio en vez de última (ver CategoryCreateOrderHttpTests).
		c.setOrden(categoryRepository.nextOrden(business.getId()) + 1);
		c.setBusiness(business);
		c.setActive(true);

		return iCategoryMapper.toDTO(categoryRepository.save(c));
	}

	@Override
	@Transactional
	public CategoryDTO actualizarCategoria(Long id, CategoryDTO dto) {

		// Mismas validaciones que la creación: el nombre es obligatorio y se
		// normaliza antes de tocar la entidad.
		if (dto.getName() == null || dto.getName().isBlank())
			throw new BusinessException("El nombre de la categoría es obligatorio", ErrorType.VALIDATION);

		// El tenant ancla en el JWT: un id de categoría ajeno al negocio
		// resuelve como 404, nunca como un éxito silencioso, y la fila ajena
		// queda exactamente igual. El negocio se toma para escritura porque la
		// edición puede cambiar el lugar de la categoría (ver lockCurrentBusiness).
		Long businessId = lockCurrentBusiness().getId();

		Category c = categoryRepository.findByIdAndBusinessId(id, businessId)
				.orElseThrow(() -> new BusinessException("Categoría no encontrada", ErrorType.NOT_FOUND));

		String normalizedName = dto.getName().trim();

		// Validamos duplicado dentro del negocio, excluyendo la propia
		// categoría (renombrar manteniendo su nombre no es duplicado).
		boolean exists = categoryRepository.existsByNameIgnoreCaseAndBusinessAndIdNot(normalizedName, businessId, id);

		if (exists) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Ya existe una categoría con ese nombre para este negocio");
		}

		c.setName(normalizedName);

		if (dto.getOrder() != null)
			c.setOrden(dto.getOrder());

		if (dto.getActive() != null)
			c.setActive(dto.getActive());

		return iCategoryMapper.toDTO(categoryRepository.save(c));
	}

	@Override
	@Transactional
	public void ordenarCategorias(List<Long> idsOrdenados) {
		// El tenant ancla en el JWT: reordenar con un id ajeno al negocio
		// resuelve como 404 y la transacción completa se descarta, dejando el
		// orden de todas las filas exactamente como estaba. El negocio se toma
		// para escritura: el reordenamiento reescribe los lugares que otro
		// escritura concurrente está leyendo (ver lockCurrentBusiness).
		Long businessId = lockCurrentBusiness().getId();

		for (int i = 0; i < idsOrdenados.size(); i++) {
			Category c = categoryRepository.findByIdAndBusinessId(idsOrdenados.get(i), businessId)
					.orElseThrow(() -> new BusinessException("Categoría no encontrada", ErrorType.NOT_FOUND));
			c.setOrden(i + 1);
			categoryRepository.save(c);
		}

	}

	@Override
	public List<CategoryDTO> findAllByBusinessId() {
		Long businessId = securityService.getCurrentBusinessId();
		return iCategoryMapper.toListDTO(categoryRepository.findActiveByBusinessIdOrdered(businessId));
	}

	@Override
	public List<CategoryDTO> findAllIncludingInactive() {
		Long businessId = securityService.getCurrentBusinessId();
		return iCategoryMapper.toListDTO(categoryRepository.findByBusinessIdOrdered(businessId));
	}

	@Override
	public CategoryDTO findById(Long id) {
		// El tenant ancla en el JWT: leer por id una categoría ajena al
		// negocio resuelve como 404, nunca filtra la fila ajena.
		Category categoria = categoryRepository
				.findByIdAndBusinessId(id, securityService.getCurrentBusinessId())
                .orElseThrow(() -> new BusinessException("Categoría no encontrada", ErrorType.NOT_FOUND));
		return iCategoryMapper.toDTO(categoria);
	}

	/**
	 * Toma la fila del negocio del JWT para escritura, o falla con el mismo 404
	 * de siempre si no existe.
	 *
	 * <p>Las tres escrituras que deciden el orden de las categorías —alta
	 * ({@code max(orden) + 1}), edición (el {@code order} del body) y
	 * reordenamiento ({@code 1..n})— son read-then-write sobre {@code orden}:
	 * sin serializar, dos escrituras concurrentes leen el mismo estado y
	 * empatan o duplican un lugar, y la carta resuelve el empate por id en vez
	 * de por el orden del dueño. El lock vive en la fila del negocio porque el
	 * negocio existe siempre (una fila conocida) y su alcance es exactamente lo
	 * que hay que serializar; ver {@code CategoryWriteSerializationTests}.</p>
	 *
	 * <p>Hibernate renderiza {@code PESSIMISTIC_WRITE} en PostgreSQL como
	 * {@code FOR NO KEY UPDATE}, que se bloquea consigo mismo y no bloquea el
	 * {@code FOR KEY SHARE} que los INSERT de pedidos toman sobre la misma
	 * fila.</p>
	 */
	private Business lockCurrentBusiness() {

		return businessRepository.findByIdForUpdate(securityService.getCurrentBusinessId())
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));
	}

	@Override
	@Transactional
	public void eliminarCategoria(Long id) {

		// El tenant ancla en el JWT: un id de categoría ajeno al negocio
		// resuelve como 404, nunca como un éxito silencioso, y la fila ajena
		// queda exactamente igual.
		Category c = categoryRepository.findByIdAndBusinessId(id, securityService.getCurrentBusinessId())
				.orElseThrow(() -> new BusinessException("Categoría no encontrada", ErrorType.NOT_FOUND));

		// Misma forma que eliminarProducto: si algo referencia la fila, la FK
		// no permitiría el borrado — fallamos de forma explícita (409) antes
		// de chocar con la constraint y terminar en un 500 genérico. El delete
		// nunca hace cascade hacia los productos.
		if (categoryRepository.hasProducts(c.getId())) {
			throw new BusinessException(
					"La categoría tiene productos asociados y no puede eliminarse",
					ErrorType.CONFLICT);
		}

		categoryRepository.delete(c);
	}

}

