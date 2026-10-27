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
import com.carrito.saas.repository.entity.BusinessDateException;
import com.carrito.saas.repository.entity.BusinessHours;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.jpa.BusinessDateExceptionRepository;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
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

	public MenuServiceImpl(BusinessRepository businessRepository, BusinessHoursRepository businessHoursRepository,
			BusinessDateExceptionRepository businessDateExceptionRepository, CategoryRepository categoryRepository,
			ProductRepository productRepository, ComboRepository comboRepository, IBusinessMapper iBusinessMapper,
			ICategoryMapper iCategoryMapper, IProductMapper iProductMapper, IComboMapper iComboMapper,
			TableRepository tableRepository) {
		this.businessRepository = businessRepository;
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
		// Siempre por slug (S4 de odd/tasks/retire-menu-route.md): la única
		// superficie pública de la carta es /{slug}; el modo slugless de un
		// solo negocio fue retirado junto con SingleBusinessResolver. Un slug
		// null/blank cae en el mismo 404 de "Local no encontrado" por
		// Optional vacío.
		Business business = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

		// Baja lógica (S5 de odd/tasks/plataforma-editar-baja.md): la carta
		// de un local dado de baja no existe — 404, tanto por slug como en el
		// modo slugless de un solo negocio. Es la superficie pública.
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
		menu.getBusiness().setHours(toSevenDayDTOs(businessHoursRepository
				.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(business.getId())));

		// Las fechas especiales viajan en el mismo payload del negocio, ordenadas
		// por fecha, con la lista de turnos vacía en las cerradas — así la carta
		// resuelve el 25/12 con la misma forma que un día de la semana.
		menu.getBusiness().setDateExceptions(toDateExceptionDTOs(businessDateExceptionRepository
				.findByBusiness_IdOrderByDateAscPositionAsc(business.getId())));

		List<CategoryDTO> categoryDTOs = categories.stream().map(iCategoryMapper::toDTO).collect(Collectors.toList());

		menu.setCategories(categoryDTOs);

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

}
