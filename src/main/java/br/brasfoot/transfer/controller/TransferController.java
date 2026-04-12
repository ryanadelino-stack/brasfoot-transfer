package br.brasfoot.transfer.controller;

import br.brasfoot.transfer.model.AnalysisReport;
import br.brasfoot.transfer.model.TransferRecord;
import br.brasfoot.transfer.model.TransferReport;
import br.brasfoot.transfer.model.TransferResult;
import br.brasfoot.transfer.service.BanFileService;
import br.brasfoot.transfer.service.BanTransferService;
import br.brasfoot.transfer.service.ExcelParserService;
import br.brasfoot.transfer.service.TransferAnalysisService;
import com.fasterxml.jackson.core.type.TypeReference;
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
 * API REST do sistema de transferências.
 *
 * POST /api/transfer/analyze  — só o Excel; retorna times envolvidos + prévia
 * POST /api/transfer/preview  — Excel + .ban; simula (retorna JSON)
 * POST /api/transfer/process  — Excel + .ban; aplica (retorna ZIP)
 * GET  /api/transfer/health   — health check
 *
 * Parâmetros opcionais nos endpoints preview/process:
 *   mappings (JSON string): { "NomeNoExcel": "nomeDoArquivoBan" }
 */
@RestController
@RequestMapping("/api/transfer")
public class TransferController {

  private static final Logger log = LoggerFactory.getLogger(TransferController.class);

  private final ExcelParserService      parser;
  private final BanFileService          banService;
  private final BanTransferService      transferService;
  private final TransferAnalysisService analysisService;
  private final ObjectMapper            objectMapper;

  public TransferController(ExcelParserService parser,
                             BanFileService banService,
                             BanTransferService transferService,
                             TransferAnalysisService analysisService) {
    this.parser           = parser;
    this.banService       = banService;
    this.transferService  = transferService;
    this.analysisService  = analysisService;
    this.objectMapper     = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);
  }

  // ─── Analyze: só o Excel ─────────────────────────────────────────────────────

  @PostMapping(value = "/analyze",
               consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
               produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AnalysisReport> analyze(
      @RequestPart("transfers") MultipartFile transferFile
  ) {
    try {
      List<TransferRecord> records = parser.parse(transferFile);
      AnalysisReport report = analysisService.analyze(records);
      log.info("Analyze: {} linhas, {} transferências, times={}", 
          records.size(), report.getPlayerTransfersDetected(), report.getTeamsInvolved());
      return ResponseEntity.ok(report);
    } catch (Exception e) {
      log.error("Erro no analyze: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  // ─── Preview: simula sem baixar ZIP ──────────────────────────────────────────

  @PostMapping(value = "/preview",
               consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
               produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<TransferReport> preview(
      @RequestPart("transfers")                              MultipartFile   transferFile,
      @RequestPart("bans")                                   MultipartFile[] banFiles,
      @RequestPart(value = "mappings", required = false)     String          mappingsJson
  ) {
    try {
      List<TransferRecord> records = parser.parse(transferFile);
      banService.loadAll(banFiles);
      applyMappings(mappingsJson);
      TransferReport report = transferService.process(records);
      return ResponseEntity.ok(report);
    } catch (Exception e) {
      log.error("Erro no preview: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  // ─── Process: aplica e retorna ZIP ───────────────────────────────────────────

  @PostMapping(value = "/process",
               consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
               produces = "application/zip")
  public ResponseEntity<byte[]> process(
      @RequestPart("transfers")                              MultipartFile   transferFile,
      @RequestPart("bans")                                   MultipartFile[] banFiles,
      @RequestPart(value = "mappings", required = false)     String          mappingsJson
  ) {
    try {
      List<TransferRecord> records = parser.parse(transferFile);
      banService.loadAll(banFiles);
      applyMappings(mappingsJson);
      TransferReport report = transferService.process(records);
      byte[] zip = buildZip(report);
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION,
              "attachment; filename=\"brasfoot-transfer-result.zip\"")
          .contentType(MediaType.parseMediaType("application/zip"))
          .body(zip);
    } catch (Exception e) {
      log.error("Erro no process: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  // ─── Health check ─────────────────────────────────────────────────────────────

  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> health() {
    return ResponseEntity.ok(Map.of(
        "status",  "ok",
        "service", "brasfoot-transfer",
        "version", "1.0.0"
    ));
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────────

  private void applyMappings(String mappingsJson) {
    if (mappingsJson == null || mappingsJson.isBlank()) return;
    try {
      Map<String, String> mappings = objectMapper.readValue(
          mappingsJson, new TypeReference<Map<String, String>>() {});
      banService.loadMappings(mappings);
      log.info("Mapeamentos manuais: {}", mappings);
    } catch (Exception e) {
      log.warn("Falha ao parsear mappings: {}", e.getMessage());
    }
  }

  private byte[] buildZip(TransferReport report) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(baos)) {
      // report.json
      byte[] reportBytes = objectMapper.writerWithDefaultPrettyPrinter()
          .writeValueAsBytes(buildReportJson(report));
      addZipEntry(zos, "report.json", reportBytes);

      // .ban modificados
      for (Map.Entry<String, byte[]> entry : banService.serializeModified().entrySet()) {
        addZipEntry(zos, entry.getKey(), entry.getValue());
      }
    }
    return baos.toByteArray();
  }

  private void addZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
    zos.putNextEntry(new ZipEntry(name));
    zos.write(data);
    zos.closeEntry();
  }

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
            "totalRows",          r.getTotalRows(),
            "successCount",       r.getSuccessCount(),
            "notFoundCount",      r.getNotFoundCount(),
            "banMissingCount",    r.getBanMissingCount(),
            "rosterFullCount",    r.getRosterFullCount(),
            "alreadyTransferred", r.getAlreadyTransferred(),
            "missingTeamCount",   r.getMissingTeamCount(),
            "financialSkipped",   r.getFinancialSkipped(),
            "uncertainSkipped",   r.getUncertainSkipped(),
            "errorCount",         r.getErrorCount(),
            "modifiedTeams",      r.getModifiedTeams()
        ),
        "transfers", items
    );
  }
}
