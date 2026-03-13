package br.brasfoot.transfer.service;

import br.brasfoot.transfer.model.TransferRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lê arquivos Excel (.xlsx) ou CSV e retorna lista de {@link TransferRecord}.
 *
 * Colunas esperadas (case-insensitive, ordem flexível):
 *   Origem (Time) | Destino (Time) | Divisao | Valor (Milhões) | Motivo | Data
 */
@Service
public class ExcelParserService {

  // Índices de coluna padrão (caso não haja cabeçalho reconhecível)
  private static final int COL_ORIGEM  = 0;
  private static final int COL_DESTINO = 1;
  private static final int COL_DIVISAO = 2;
  private static final int COL_VALOR   = 3;
  private static final int COL_MOTIVO  = 4;
  private static final int COL_DATA    = 5;

  public List<TransferRecord> parse(MultipartFile file) throws IOException {
    String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
    if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
      return parseExcel(file);
    } else {
      return parseCsv(file);
    }
  }

  // ─── Excel ──────────────────────────────────────────────────────────────────

  private List<TransferRecord> parseExcel(MultipartFile file) throws IOException {
    List<TransferRecord> records = new ArrayList<>();

    try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
      Sheet sheet = wb.getSheetAt(0);

      // Descobre índices de coluna lendo o cabeçalho (primeira linha não-vazia)
      int[] colIdx = detectColumns(sheet);

      boolean firstRow = true;
      for (Row row : sheet) {
        if (row == null) continue;
        if (firstRow) { firstRow = false; continue; } // pula cabeçalho

        String origem  = cellStr(row, colIdx[0]);
        String destino = cellStr(row, colIdx[1]);
        String divisao = cellStr(row, colIdx[2]);
        String valor   = cellStr(row, colIdx[3]);
        String motivo  = cellStr(row, colIdx[4]);
        String data    = cellStr(row, colIdx[5]);

        // Ignora linhas completamente vazias
        if (origem.isBlank() && destino.isBlank() && motivo.isBlank()) continue;

        records.add(new TransferRecord(row.getRowNum() + 1,
            origem, destino, divisao, valor, motivo, data));
      }
    }
    return records;
  }

  /**
   * Tenta detectar a posição de cada coluna pelo cabeçalho.
   * Se falhar, usa a ordem padrão.
   */
  private int[] detectColumns(Sheet sheet) {
    int[] idx = {COL_ORIGEM, COL_DESTINO, COL_DIVISAO, COL_VALOR, COL_MOTIVO, COL_DATA};

    Row header = sheet.getRow(0);
    if (header == null) return idx;

    for (Cell cell : header) {
      String h = cellStr(cell).toLowerCase().strip();
      int c = cell.getColumnIndex();
      if (h.contains("origem"))  idx[0] = c;
      if (h.contains("destino")) idx[1] = c;
      if (h.contains("divis"))   idx[2] = c;
      if (h.contains("valor"))   idx[3] = c;
      if (h.contains("motivo"))  idx[4] = c;
      if (h.contains("data"))    idx[5] = c;
    }
    return idx;
  }

  private String cellStr(Row row, int col) {
    Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    return cellStr(cell);
  }

  private String cellStr(Cell cell) {
    if (cell == null) return "";
    return switch (cell.getCellType()) {
      case STRING  -> cell.getStringCellValue();
      case NUMERIC -> {
        if (DateUtil.isCellDateFormatted(cell)) {
          yield cell.getLocalDateTimeCellValue().toString();
        }
        double d = cell.getNumericCellValue();
        yield (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
      }
      case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
      case FORMULA -> {
        try { yield cell.getStringCellValue(); }
        catch (Exception e) { yield String.valueOf(cell.getNumericCellValue()); }
      }
      default -> "";
    };
  }

  // ─── CSV ────────────────────────────────────────────────────────────────────

  private List<TransferRecord> parseCsv(MultipartFile file) throws IOException {
    List<TransferRecord> records = new ArrayList<>();

    try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
         CSVParser parser = CSVFormat.DEFAULT.builder()
             .setHeader()
             .setSkipHeaderRecord(true)
             .setIgnoreHeaderCase(true)
             .setTrim(true)
             .build()
             .parse(reader)) {

      int row = 2;
      for (CSVRecord r : parser) {
        String origem  = safeGet(r, "origem (time)", "origem");
        String destino = safeGet(r, "destino (time)", "destino");
        String divisao = safeGet(r, "divisao", "divisão");
        String valor   = safeGet(r, "valor (milhões)", "valor");
        String motivo  = safeGet(r, "motivo");
        String data    = safeGet(r, "data");

        if (origem.isBlank() && destino.isBlank() && motivo.isBlank()) { row++; continue; }
        records.add(new TransferRecord(row++, origem, destino, divisao, valor, motivo, data));
      }
    }
    return records;
  }

  private String safeGet(CSVRecord r, String... keys) {
    for (String k : keys) {
      try { String v = r.get(k); if (v != null) return v; }
      catch (Exception ignored) {}
    }
    return "";
  }
}
