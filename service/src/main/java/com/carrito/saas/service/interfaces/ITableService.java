package com.carrito.saas.service.interfaces;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.carrito.saas.dto.CreateTableRequestDTO;
import com.carrito.saas.dto.GenerateTablesRequestDTO;
import com.carrito.saas.dto.QrPdfRequestDTO;
import com.carrito.saas.dto.TableResponseDTO;
import com.carrito.saas.dto.UpdateTableRequestDTO;

/**
 * Este servicio es el encargado de : - CRUD mesas -Generación masiva Generación
 * token QR Descarga PDF
 */
public interface ITableService {

	byte[] generateQr(Long tableId, Integer size);

	byte[] generatePdf(Long tableId, QrPdfRequestDTO config);

	byte[] generatePdfAll(QrPdfRequestDTO config);

	TableResponseDTO create(CreateTableRequestDTO request);

	TableResponseDTO update(Long id, UpdateTableRequestDTO request);

	void delete(Long id);

	TableResponseDTO findById(Long tableId);

	List<TableResponseDTO> generateBulk(GenerateTablesRequestDTO request);

	Page<TableResponseDTO> findAll(Pageable pageable);

}
