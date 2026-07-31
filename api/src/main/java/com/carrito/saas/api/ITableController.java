package com.carrito.saas.api;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.carrito.saas.dto.CreateTableRequestDTO;
import com.carrito.saas.dto.GenerateTablesRequestDTO;
import com.carrito.saas.dto.QrPdfRequestDTO;
import com.carrito.saas.dto.TableResponseDTO;
import com.carrito.saas.dto.UpdateTableRequestDTO;

import jakarta.validation.Valid;

public interface ITableController {

	public ResponseEntity<TableResponseDTO> create(@Valid @RequestBody CreateTableRequestDTO request);

	public TableResponseDTO update(@PathVariable Long id, @Valid @RequestBody UpdateTableRequestDTO request);

	public void delete(@PathVariable Long id);

	public Page<TableResponseDTO> findAll(Pageable pageable);

	public List<TableResponseDTO> generateBulk(@Valid @RequestBody GenerateTablesRequestDTO request);

	/**
	 * Descargar QR PNG
	 * 
	 * @param id
	 * @return
	 */
	public ResponseEntity<byte[]> downloadQr(@PathVariable Long id, @RequestParam(required = false) Integer size);

	/**
	 * Descargar PDF Individual
	 * 
	 * @param id
	 * @return
	 */
	public ResponseEntity<byte[]> downloadPdf(@PathVariable Long id, @RequestBody QrPdfRequestDTO request);

	/**
	 * Descargar Todos
	 * 
	 * @return
	 */
	public ResponseEntity<byte[]> downloadAllPdf(@RequestBody QrPdfRequestDTO request);

}
