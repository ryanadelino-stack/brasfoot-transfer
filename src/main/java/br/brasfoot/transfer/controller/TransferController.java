package br.brasfoot.transfer.controller;

import br.brasfoot.transfer.model.TransferRecord;
import br.brasfoot.transfer.model.TransferReport;
import br.brasfoot.transfer.model.TransferResult;
import br.brasfoot.transfer.service.BanFileService;
import br.brasfoot.transfer.service.BanTransferService;
import br.brasfoot.transfer.service.ExcelParserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * API REST para o sistema de transferências.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * POST /api/transfer/process
 *   Parâmetros (multipart/form-data):
 *     - transfers : arquivo Excel (.xlsx) ou CSV com as transferências da liga
 *     - bans      : um ou mais arquivos .ban (um por clube)
 *
 *   Resposta: arquivo ZIP contendo:
 *     - report.json          : relatório completo em JSON
 *     - <NomeClube>.ban      : arquivos .ban modificados
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * POST /api/transfer/preview
 *   Mesmos parâmetros, mas retorna apenas o relatório JSON (sem modificar nada).
 *   Útil para revisar o que seria feito antes de aplicar.
 * ──────────────────────────────────────────────────────────────────────────────
 */
@RestController
@RequestMapping("/api/transfer")
public class TransferController {

  private static final Logger log = LoggerFactory.getLogger(TransferController.class);

  private final ExcelParserService  parser;
  private final BanFileService      banService;
  private final BanTransferService  transferService;
  private final ObjectMapper        objectMapper;

  public TransferController(ExcelParserService parser,
                             BanFileService banService,
                             BanTransferService transferService) {
    this.parser          = parser;
    this.banService      = banService;
    this.transferService = transferService;
    this.objectMapper    = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);
  }

  // ─── Endpoint principal: processa e retorna ZIP ──────────────────────────────

  @PostMapping(
      value   = "/process",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = "application/zip"
  )
  public ResponseEntity<byte[]> process(
      @RequestPart("transfers") MultipartFile transferFile,
      @RequestPart("bans")      MultipartFile[] banFiles
  ) {
    try {
      // 1. Parse Excel/CSV
      List<TransferRecord> records = parser.parse(transferFile);
      log.info("Arquivo de transferências lido: {} linhas", records.size());

      // 2. Carrega todos os .ban
      banService.loadAll(banFiles);
      log.info("Arquivos .ban carregados: {}", banFiles.length);

      // 3. Processa transferências
      TransferReport report = transferService.process(records);
      logSummary(report);

      // 4. Monta o ZIP de resposta
      byte[] zip = buildZip(report);

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"brasfoot-transfer-result.zip\"")
          .contentType(MediaType.parseMediaType("application/zip"))
          .body(zip);

    } catch (Exception e) {
      log.error("Erro no processamento de transferências: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  // ─── Preview: só o relatório, sem modificar .ban ────────────────────────────

  @PostMapping(
      value    = "/preview",
      consumes  = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces  = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<TransferReport> preview(
      @RequestPart("transfers") MultipartFile transferFile,
      @RequestPart("bans")      MultipartFile[] banFiles
  ) {
    try {
      List<TransferRecord> records = parser.parse(transferFile);
      banService.loadAll(banFiles);
      TransferReport report = transferService.process(records);
      return ResponseEntity.ok(report);
    } catch (Exception e) {
      log.error("Erro no preview: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  // ─── Health check ────────────────────────────────────────────────────────────

  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> health() {
    return ResponseEntity.ok(Map.of(
        "status",  "ok",
        "service", "brasfoot-transfer",
        "version", "1.0.0"
    ));
  }

  // ─── Montagem do ZIP ─────────────────────────────────────────────────────────

  private byte[] buildZip(TransferReport report) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    try (ZipOutputStream zos = new ZipOutputStream(baos)) {

      // 1. report.json
      byte[] reportBytes = objectMapper
          .writerWithDefaultPrettyPrinter()
          .writeValueAsBytes(buildReportJson(report));
      addEntry(zos, "report.json", reportBytes);

      // 2. Arquivos .ban modificados
      Map<String, byte[]> modifiedBans = banService.serializeModified();
      for (Map.Entry<String, byte[]> entry : modifiedBans.entrySet()) {
        addEntry(zos, entry.getKey(), entry.getValue());
      }
    }

    return baos.toByteArray();
  }

  private void addEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    zos.putNextEntry(entry);
    zos.write(data);
    zos.closeEntry();
  }

  /** Constrói um mapa simplificado para serialização JSON do relatório */
  private Map<String, Object> buildReportJson(TransferReport r) {
    List<Map<String, Object>> items = r.getResults().stream()
        .map(res -> Map.<String, Object>of(
            "row",         res.getRowIndex(),
            "player",      res.getPlayerName(),
            "from",        res.getFromTeam(),
            "to",          res.getToTeam(),
            "motivo",      res.getRawMotivo(),
            "status",      res.getStatus().name(),
            "message",     res.getMessage(),
            "matchedName", res.getMatchedName(),
            "matchScore",  String.format("%.0f%%", res.getMatchScore() * 100)
        ))
        .toList();

    return Map.of(
        "summary", Map.of(
            "totalRows",        r.getTotalRows(),
            "successCount",     r.getSuccessCount(),
            "notFoundCount",    r.getNotFoundCount(),
            "banMissingCount",  r.getBanMissingCount(),
            "financialSkipped", r.getFinancialSkipped(),
            "uncertainSkipped", r.getUncertainSkipped(),
            "errorCount",       r.getErrorCount(),
            "modifiedTeams",    r.getModifiedTeams()
        ),
        "transfers", items
    );
  }

  private void logSummary(TransferReport r) {
    log.info("=== RELATÓRIO DE TRANSFERÊNCIAS ===");
    log.info("Total linhas:        {}", r.getTotalRows());
    log.info("Transferidos:        {}", r.getSuccessCount());
    log.info("Não encontrados:     {}", r.getNotFoundCount());
    log.info(".ban ausentes:       {}", r.getBanMissingCount());
    log.info("Financeiros (skip):  {}", r.getFinancialSkipped());
    log.info("Incertos (skip):     {}", r.getUncertainSkipped());
    log.info("Erros:               {}", r.getErrorCount());
    log.info("Times modificados:   {}", r.getModifiedTeams());
  }
}
