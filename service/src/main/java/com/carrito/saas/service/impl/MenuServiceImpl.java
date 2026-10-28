package com.carrito.saas.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.BusinessDTO;
import com.carrito.saas.dto.BranchDTO;
import com.carrito.saas.dto.BusinessHoursDTO;
import com.carrito.saas.dto.BusinessShiftDTO;
import com.carrito.saas.dto.CategoryDTO;
import com.carrito.saas.dto.ComboDTO;
import com.carrito.saas.dto.DateExceptionDTO;
import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.dto.MenuTableDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Branch;
import com.carrito.saas.repository.entity.BusinessDateException;
import com.carrito.saas.repository.entity.BusinessHours;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.jpa.BusinessDateExceptionRepository;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.repository.jpa.BranchRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.TableRepository;
import com.carrito.saas.service.interfaces.IMenuService;
import com.carrito.saas.service.mapper.interfaces.IBusinessMapper;
import com.carrito.saas.service.mapper.interfaces.ICategoryMapper;
import com.carrito.saas.service.mapper.interfaces.IComboMapper;
import com.carrito.saas.service.mapper.interfaces.IProductMapper;

@Service
public class MenuServiceImpl implements IMenuService {

	private final BusinessRepository businessRepository;
	private final BranchRepository branchRepository;
	private final BusinessHoursRepository businessHoursRepository;
	private final BusinessDateExceptionRepository businessDateExceptionRepository;
	private final CategoryRepository categoryRepository;
	private final ProductRepository productRepository;
	private final ComboRepository comboRepository;
	private final IBusinessMapper iBusinessMapper;
	private final ICategoryMapper iCategoryMapper;
	private final IProductMapper iProductMapper;
	private final IComboMapper iComboMapper;

	private final TableRepository tableRepository;

	public MenuServiceImpl(BusinessRepository businessRepository, BranchRepository branchRepository,
			BusinessHoursRepository businessHoursRepository,
			BusinessDateExceptionRepository businessDateExceptionRepository, CategoryRepository categoryRepository,
			ProductRepository productRepository, ComboRepository comboRepository, IBusinessMapper iBusinessMapper,
			ICategoryMapper iCategoryMapper, IProductMapper iProductMapper, IComboMapper iComboMapper,
			TableRepository tableRepository) {
		this.businessRepository = businessRepository;
		this.branchRepository = branchRepository;
		this.businessHoursRepository = businessHoursRepository;
		this.businessDateExceptionRepository = businessDateExceptionRepository;
		this.categoryRepository = categoryRepository;
		this.productRepository = productRepository;
		this.comboRepository = comboRepository;
		this.iBusinessMapper = iBusinessMapper;
		this.iCategoryMapper = iCategoryMapper;
		this.iProductMapper = iProductMapper;
		this.iComboMapper = iComboMapper;
		this.tableRepository = tableRepository;
	}

	@Override
	public MenuTableDTO getPublicTable(String slug, Long tableId) {

		// Slug-scoped (S4 enmendada de odd/tasks/retire-menu-route.md): el
		// negocio se resuelve del slug; un slug desconocido cae en el mismo
		// 404 "Local no encontrado" que la carta.
		Business business = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

		// Baja lógica (S5 de odd/tasks/plataforma-editar-baja.md): la mesa de
		// un local dado de baja no existe públicamente — 404, igual que la
		// carta.
		if (!Boolean.TRUE.equals(business.getActive())) {
			throw new BusinessException("Local no encontrado", ErrorType.NOT_FOUND);
		}

		// An unknown id OR a table of another business: same 404, without
		// filtering existence (same rule OrderServiceImpl applies for SALON).
		// El scoping por negocio es lo que preserva el aislamiento entre
		// locales que el resolver slugless daba fail-closed.
		RestaurantTable table = tableRepository
				.findByIdAndBusinessId(tableId, business.getId())
				.orElseThrow(() -> new BusinessException("Mesa no encontrada", ErrorType.NOT_FOUND));

		return MenuTableDTO.builder()
				.id(table.getId())
				.tableNumber(table.getTableNumber())
				.tableName(table.getTableName())
				.build();
	}

	@Override
	public MenuDTO getMenu(String slug) {
		return getMenu(slug, null);
	}

	@Override
	public MenuDTO getMenu(String slug, Long requestedBranchId) {
		// Siempre por slug (S4 de odd/tasks/retire-menu-route.md): la única
		// superficie pública de la carta es /{slug}; el modo slugless de un
		// solo negocio fue retirado junto con SingleBusinessResolver. Un slug
		// null/blank cae en el mismo 404 de "Local no encontrado" por
		// Optional vacío.
		// Multi-sucursal (S4 de odd/tasks/multi-sucursal.md): requestedBranchId
		// (opcional) scopea horas/WhatsApp/estado a esa sucursal con fallback
		// silencioso (resolveMenuBranch); el slug sigue obligatorio.
		Business business = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

		// Baja lógica (S5 de odd/tasks/plataforma-editar-baja.md): la carta
		// de un local dado de baja no existe — 404. Es la superficie pública.
		if (!Boolean.TRUE.equals(business.getActive())) {
			throw new BusinessException("Local no encontrado", ErrorType.NOT_FOUND);
		}

		List<Category> categories = categoryRepository.findByBusinessId(business.getId());

		// Buscamos los productos del negocio que esten activos y ofrecibles:
		// stock null (infinito) o stock > 0; stock 0 no se ofrece.
		List<Product> products = productRepository.findPublicMenuProducts(business.getId());

		// NUEVO: traer combos
		// Carta pública: sólo combos ACTIVOS (el switch del panel saca de la
		// carta los apagados); la regla vive en la consulta, como en productos.
		List<Combo> combos = comboRepository.findActiveFullMenuCombos(business.getId());

		MenuDTO menu = new MenuDTO();

		menu.setBusiness(iBusinessMapper.toDTO(business));

		// Storefront: las horas se traen acá (la entidad Business no las
		// colecciona) para que el menú no necesite una segunda llamada. La
		// respuesta lleva siempre los siete días (1..7) con su lista de
		// turnos — vacía cuando el día está cerrado—, así la carta no tiene
		// que inferir "el día que falta es un día cerrado".
		// Multi-sucursal (odd/tasks/multi-sucursal.md, S3/T4 y S4/T7): la
		// carta pública sirve las horas de la sucursal pedida por ?sucursal=
		// cuando es propia y activa; si no, la default (la primera activa por
		// id); negocio sin sucursales → vista legacy a nivel negocio.
		Branch menuBranch = resolveMenuBranch(business, requestedBranchId);
		Long menuBranchId = menuBranch == null ? null : menuBranch.getId();

		// WhatsApp por sucursal (multi-sucursal, odd/tasks/multi-sucursal.md,
		// S3/T5): el wa.me de la carta usa el número de la sucursal servida si
		// tiene uno propio; si no, el del negocio (fallback legacy, el que el
		// mapper ya trajo). Negocio sin sucursales → exactamente el número del
		// negocio, comportamiento anterior intacto.
		if (menuBranch != null && menuBranch.getPhone() != null) {
			menu.getBusiness().setWhatsappNumber(menuBranch.getPhone());
		}
		menu.getBusiness().setHours(toSevenDayDTOs(businessHoursRepository
				.findByBusinessIdAndOptionalBranch(business.getId(), menuBranchId)));

		// Las fechas especiales viajan en el mismo payload del negocio, ordenadas
		// por fecha, con la lista de turnos vacía en las cerradas — así la carta
		// resuelve el 25/12 con la misma forma que un día de la semana.
		menu.getBusiness().setDateExceptions(toDateExceptionDTOs(businessDateExceptionRepository
				.findByBusinessIdAndOptionalBranch(business.getId(), menuBranchId)));

		List<CategoryDTO> categoryDTOs = categories.stream().map(iCategoryMapper::toDTO).collect(Collectors.toList());

		menu.setCategories(categoryDTOs);

		// Payload de sucursales activas (multi-sucursal,
		// odd/tasks/multi-sucursal.md, S4/T8-T10): el payload del menú lleva la
		// lista de sucursales ACTIVAS (id/name/address, ordenadas por id) para
		// que la pantalla previa (BranchPicker) ofrezca la elección — el
		// parámetro ?sucursal= y el branchId del checkout (S4) apuntan a estos
		// ids. La carta NUNCA muestra un control de sucursal y la carta es
		// compartida
		// (S2): la lista identifica lugares, NO bifurca productos ni precios.
		// Negocio sin sucursales → lista vacía, UI idéntica a la de hoy. Sólo
		// viajan los tres campos públicos del selector: el resto del BranchDTO
		// queda null (phone/active/businessId son datos del panel, no de la
		// carta anónima).
		List<BranchDTO> branchDTOs = branchRepository
				.findByBusinessIdAndActiveTrueOrderByIdAsc(business.getId())
				.stream()
				.map(branch -> {
					BranchDTO dto = new BranchDTO();
					dto.setId(branch.getId());
					dto.setName(branch.getName());
					dto.setAddress(branch.getAddress());
					return dto;
				})
				.collect(Collectors.toList());
		menu.setBranches(branchDTOs);

		List<ProductDTO> productDTOs = products.stream().map(iProductMapper::toDTO).collect(Collectors.toList());

		menu.setProducts(productDTOs);

		// NUEVO: mapear combos
		List<ComboDTO> comboDTOs = combos.stream().map(iComboMapper::toDTO).collect(Collectors.toList());

		menu.setCombos(comboDTOs);

		return menu;
	}

	/** Igual que en {@code BusinessProfileServiceImpl}: los siete días, con la lista vacía en los cerrados. */
	private static List<BusinessHoursDTO> toSevenDayDTOs(List<BusinessHours> rows) {

		Map<Integer, List<BusinessShiftDTO>> shiftsByDay = new HashMap<>();
		for (BusinessHours row : rows) {
			BusinessShiftDTO shift = new BusinessShiftDTO();
			shift.setOpensAt(row.getOpensAt());
			shift.setClosesAt(row.getClosesAt());
			shiftsByDay.computeIfAbsent(row.getDayOfWeek(), day -> new ArrayList<>()).add(shift);
		}

		List<BusinessHoursDTO> days = new ArrayList<>(7);
		for (int day = 1; day <= 7; day++) {
			BusinessHoursDTO dto = new BusinessHoursDTO();
			dto.setDayOfWeek(day);
			dto.setShifts(shiftsByDay.getOrDefault(day, List.of()));
			days.add(dto);
		}
		return days;
	}

	/** Igual que en {@code BusinessProfileServiceImpl}: las fechas ordenadas, con la lista vacía en las cerradas. */
	private static List<DateExceptionDTO> toDateExceptionDTOs(List<BusinessDateException> rows) {

		// Las filas vienen por (date, position): el LinkedHashMap conserva el
		// orden de fechas. La fila marcadora (opensAt NULL) deja la lista vacía.
		Map<LocalDate, List<BusinessShiftDTO>> shiftsByDate = new LinkedHashMap<>();
		Map<LocalDate, Boolean> yearlyByDate = new LinkedHashMap<>();
		for (BusinessDateException row : rows) {
			List<BusinessShiftDTO> shifts = shiftsByDate.computeIfAbsent(row.getDate(), date -> new ArrayList<>());
			// La bandera se lee ANTES del continue: un feriado cerrado también
			// puede repetirse cada año, y su fila marcadora no aporta turnos.
			yearlyByDate.put(row.getDate(), row.isYearly());
			if (row.getOpensAt() == null) {
				continue;
			}
			BusinessShiftDTO shift = new BusinessShiftDTO();
			shift.setOpensAt(row.getOpensAt());
			shift.setClosesAt(row.getClosesAt());
			shifts.add(shift);
		}

		List<DateExceptionDTO> dates = new ArrayList<>(shiftsByDate.size());
		for (Map.Entry<LocalDate, List<BusinessShiftDTO>> entry : shiftsByDate.entrySet()) {
			DateExceptionDTO dto = new DateExceptionDTO();
			dto.setDate(entry.getKey());
			dto.setShifts(entry.getValue());
			// Sin esto la carta pública nunca vería un feriado anual: el perfil
			// del dueño lo mapeaba y este gemelo se había quedado atrás.
			dto.setYearly(Boolean.TRUE.equals(yearlyByDate.get(entry.getKey())));
			dates.add(dto);
		}
		return dates;
	}

	/**
	 * Sucursal servida por la carta (multi-sucursal, S4/T7): la pedida por
	 * ?sucursal= cuando es propia y activa; inválida/ajena/inactiva o sin
	 * parámetro → la default (primera activa). FALLBACK SILENCIOSO a
	 * propósito: un link con una sucursal dada de baja no puede romper la
	 * carta del cliente — es display público. Los pedidos siguen fail-closed
	 * (404 en OrderServiceImpl.resolveBranchForOrder): esa asimetría es el
	 * contrato, no una inconsistencia.
	 */
	private Branch resolveMenuBranch(Business business, Long requestedBranchId) {

		if (requestedBranchId != null) {
			Branch requested = branchRepository.findByIdAndBusinessId(requestedBranchId, business.getId())
					.filter(b -> Boolean.TRUE.equals(b.getActive()))
					.orElse(null);
			if (requested != null) {
				return requested;
			}
		}

		return branchRepository.findFirstByBusinessIdAndActiveTrueOrderByIdAsc(business.getId());
	}

}
