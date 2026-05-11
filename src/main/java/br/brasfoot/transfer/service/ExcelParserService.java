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
 * Suporta três formatos de planilha:
 *
 * FORMATO A (vírgula, EBL original):
 *   Origem (Time) | Destino (Time) | Divisao | Valor (Milhões) | Motivo | Data
 *
 * FORMATO B (ponto-e-vírgula, sistema de transações financeiras):
 *   ID | Data/Hora | Time Remetente | ... | Time Destinatário | ... | Motivo | ...
 *   Remetente = comprador | Destinatário = vendedor (cede o jogador)
 *
 * FORMATO C (ponto-e-vírgula, planilha direta de transferências):
 *   ID | Data/Hora | Jogador | Time Origem | Time Destino | Status
 *   Formato mais simples: jogador e times em colunas dedicadas.
 *   Apenas linhas com Status = "concluido" são processadas.
 *
 * A detecção é automática pelo cabeçalho (Formato C detectado pela presença de "Jogador").
 */
@Service
public class ExcelParserService {

  public List<TransferRecord> parse(MultipartFile file) throws IOException {
    String name = file.getOriginalFilename() == null
        ? "" : file.getOriginalFilename().toLowerCase();
    if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
      return parseExcel(file);
    } else {
      return parseCsv(file);
    }
  }

  // ─── Excel ───────────────────────────────────────────────────────────────────

  private List<TransferRecord> parseExcel(MultipartFile file) throws IOException {
    List<TransferRecord> records = new ArrayList<>();

    try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
      Sheet sheet = wb.getSheetAt(0);
      int[] colIdx = detectExcelColumns(sheet);

      boolean firstRow = true;
      for (Row row : sheet) {
        if (row == null) continue;
        if (firstRow) { firstRow = false; continue; }

        String origem  = cellStr(row, colIdx[0]);
        String destino = cellStr(row, colIdx[1]);
        String motivo  = cellStr(row, colIdx[2]);
        String valor   = colIdx[3] >= 0 ? cellStr(row, colIdx[3]) : "";
        String data    = colIdx[4] >= 0 ? cellStr(row, colIdx[4]) : "";

        if (origem.isBlank() && destino.isBlank() && motivo.isBlank()) continue;
        records.add(new TransferRecord(row.getRowNum() + 1,
            origem, destino, "", valor, motivo, data));
      }
    }
    return records;
  }

  /**
   * Detecta as colunas pelo cabeçalho.
   * Retorna int[5]: [origemIdx, destinoIdx, motivoIdx, valorIdx, dataIdx]
   * Suporta ambos os formatos de planilha.
   */
  private int[] detectExcelColumns(Sheet sheet) {
    // Padrão seguro para o Formato A
    int[] idx = {0, 1, 4, 3, 5};

    Row header = sheet.getRow(0);
    if (header == null) return idx;

    for (Cell cell : header) {
      String h = cellStr(cell).toLowerCase().strip();
      int c = cell.getColumnIndex();

      // Formato A
      if (h.contains("origem"))    idx[0] = c;
      if (h.contains("destino"))   idx[1] = c;
      if (h.contains("motivo"))    idx[2] = c;
      if (h.contains("valor"))     idx[3] = c;
      if (h.contains("data") && !h.contains("hora")) idx[4] = c;

      // Formato B
      if (h.equals("time remetente") || h.contains("remetente") && h.contains("time")) idx[0] = c;
      if (h.equals("time destinatário") || h.equals("time destinatario")
          || (h.contains("destinat") && h.contains("time")))                           idx[1] = c;
    }
    return idx;
  }

  // ─── CSV ─────────────────────────────────────────────────────────────────────

  private List<TransferRecord> parseCsv(MultipartFile file) throws IOException {
    // Lê o conteúdo completo para detectar o separador
    byte[] bytes = file.getInputStream().readAllBytes();
    String raw = new String(bytes, StandardCharsets.UTF_8)
        .replace("\uFEFF", ""); // Remove BOM

    // Detecta separador e formato pelo cabeçalho
    String firstLine = raw.lines().findFirst().orElse("");
    char delimiter = firstLine.chars().filter(c -> c == ';').count()
        > firstLine.chars().filter(c -> c == ',').count() ? ';' : ',';

    if (delimiter == ';') {
      // Formato C: tem coluna "Jogador" dedicada no cabeçalho
      String firstLineLower = firstLine.toLowerCase();
      if (firstLineLower.contains("jogador")
          && firstLineLower.contains("origem")
          && firstLineLower.contains("destino")) {
        return parseCsvFormatoC(raw, delimiter);
      }
      return parseCsvFormatoB(raw, delimiter);
    } else {
      return parseCsvFormatoA(raw, delimiter);
    }
  }

  /**
   * FORMATO A — vírgula, cabeçalho: "Origem (Time)", "Destino (Time)", "Motivo"
   */
  private List<TransferRecord> parseCsvFormatoA(String raw, char delimiter) throws IOException {
    List<TransferRecord> records = new ArrayList<>();

    try (Reader reader = new java.io.StringReader(raw);
         CSVParser parser = CSVFormat.DEFAULT.builder()
             .setDelimiter(delimiter)
             .setHeader()
             .setSkipHeaderRecord(true)
             .setIgnoreHeaderCase(true)
             .setTrim(true)
             .build()
             .parse(reader)) {

      int rowNum = 2;
      for (CSVRecord r : parser) {
        String origem  = safeGet(r, "origem (time)", "origem");
        String destino = safeGet(r, "destino (time)", "destino");
        String motivo  = safeGet(r, "motivo");
        String valor   = safeGet(r, "valor (milhões)", "valor");
        String data    = safeGet(r, "data");

        if (origem.isBlank() && destino.isBlank() && motivo.isBlank()) { rowNum++; continue; }
        records.add(new TransferRecord(rowNum++, origem, destino, "", valor, motivo, data));
      }
    }
    return records;
  }

  /**
   * FORMATO B — ponto-e-vírgula, cabeçalho:
   *   ID | Data/Hora | Time Remetente | ... | Time Destinatário | ... | Motivo | ...
   *
   * Mapeamento fixo + fallback por nome de cabeçalho:
   *   C (idx 2) = Time Remetente  → origem
   *   F (idx 5) = Time Destinatário → destino
   *   J (idx 9) = Motivo
   */
  private List<TransferRecord> parseCsvFormatoB(String raw, char delimiter) throws IOException {
    List<TransferRecord> records = new ArrayList<>();

    try (Reader reader = new java.io.StringReader(raw);
         CSVParser parser = CSVFormat.DEFAULT.builder()
             .setDelimiter(delimiter)
             .setHeader()
             .setSkipHeaderRecord(true)
             .setIgnoreHeaderCase(true)
             .setTrim(true)
             .setQuote('"')
             .build()
             .parse(reader)) {

      // Descobre os índices pelo cabeçalho (case-insensitive)
      // com fallback nos índices posicionais do Formato B
      java.util.Map<String, Integer> headerMap = parser.getHeaderMap();
      int origemIdx  = resolveIdx(headerMap, 2,  "time remetente", "remetente");
      int destinoIdx = resolveIdx(headerMap, 5,  "time destinatário", "time destinatario", "destinatário", "destinatario");
      int motivoIdx  = resolveIdx(headerMap, 9,  "motivo");
      int valorIdx   = resolveIdx(headerMap, 8,  "valor (r$)", "valor");
      int dataIdx    = resolveIdx(headerMap, 1,  "data/hora", "data");

      int rowNum = 2;
      for (CSVRecord r : parser) {
        String origem  = safeGetByIdx(r, origemIdx);
        String destino = safeGetByIdx(r, destinoIdx);
        String motivo  = safeGetByIdx(r, motivoIdx);
        String valor   = safeGetByIdx(r, valorIdx);
        String data    = safeGetByIdx(r, dataIdx);

        if (origem.isBlank() && destino.isBlank() && motivo.isBlank()) { rowNum++; continue; }
        records.add(new TransferRecord(rowNum++, origem, destino, "", valor, motivo, data));
      }
    }
    return records;
  }

  /**
   * FORMATO C — ponto-e-vírgula com colunas dedicadas:
   *   ID | Data/Hora | Jogador | Time Origem | Time Destino | Status
   *
   * Swap intencional de origem/destino: BanTransferService usa
   *   fromTeam = record.getDestino()  e  toTeam = record.getOrigem()
   * Por isso armazenamos Time Origem em destino e Time Destino em origem
   * para que o resultado após o swap seja o correto.
   */
  private List<TransferRecord> parseCsvFormatoC(String raw, char delimiter) throws IOException {
    List<TransferRecord> records = new ArrayList<>();

    try (Reader reader = new java.io.StringReader(raw);
         CSVParser parser = CSVFormat.DEFAULT.builder()
             .setDelimiter(delimiter)
             .setHeader()
             .setSkipHeaderRecord(true)
             .setIgnoreHeaderCase(true)
             .setTrim(true)
             .setQuote('"')
             .build()
             .parse(reader)) {

      java.util.Map<String, Integer> headerMap = parser.getHeaderMap();
      int jogadorIdx     = resolveIdx(headerMap, 2, "jogador");
      int timeOrigemIdx  = resolveIdx(headerMap, 3, "time origem", "origem");
      int timeDestinoIdx = resolveIdx(headerMap, 4, "time destino", "destino");
      int statusIdx      = resolveIdx(headerMap, 5, "status");
      int dataIdx        = resolveIdx(headerMap, 1, "data/hora", "data");

      int rowNum = 2;
      for (CSVRecord r : parser) {
        // Filtra por status — só processa transferências concluídas
        String status = safeGetByIdx(r, statusIdx).toLowerCase().strip();
        if (!status.isBlank()
            && !status.equals("concluido")
            && !status.equals("concluída")
            && !status.equals("concluida")) {
          rowNum++;
          continue;
        }

        String jogador     = safeGetByIdx(r, jogadorIdx);
        String timeOrigem  = safeGetByIdx(r, timeOrigemIdx);
        String timeDestino = safeGetByIdx(r, timeDestinoIdx);
        String data        = safeGetByIdx(r, dataIdx);

        if (jogador.isBlank() && timeOrigem.isBlank() && timeDestino.isBlank()) {
          rowNum++;
          continue;
        }

        // Swap: BanTransferService lê fromTeam=getDestino(), toTeam=getOrigem()
        // Então: origem=timeDestino (recebe), destino=timeOrigem (cede)
        records.add(new TransferRecord(rowNum++,
            timeDestino,  // getOrigem() → toTeam (time que recebe)
            timeOrigem,   // getDestino() → fromTeam (time que cede)
            "", "", jogador, data));
      }
    }
    return records;
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────────

  private int resolveIdx(java.util.Map<String, Integer> headerMap,
                          int defaultIdx, String... candidates) {
    if (headerMap == null) return defaultIdx;
    for (String key : candidates) {
      for (java.util.Map.Entry<String, Integer> entry : headerMap.entrySet()) {
        if (entry.getKey().toLowerCase().strip().equals(key.toLowerCase())) {
          return entry.getValue();
        }
      }
    }
    return defaultIdx;
  }

  private String safeGetByIdx(CSVRecord r, int idx) {
    try {
      if (idx < 0 || idx >= r.size()) return "";
      String v = r.get(idx);
      return v == null ? "" : v.strip();
    } catch (Exception e) { return ""; }
  }

  private String safeGet(CSVRecord r, String... keys) {
    for (String k : keys) {
      try { String v = r.get(k); if (v != null) return v.strip(); }
      catch (Exception ignored) {}
    }
    return "";
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
}
