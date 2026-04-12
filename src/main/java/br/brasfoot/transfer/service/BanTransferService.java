package br.brasfoot.transfer.service;

import br.brasfoot.transfer.model.*;
import br.brasfoot.transfer.util.StringNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Orquestra o processo completo de transferência.
 *
 * SOBRE A DIREÇÃO DAS TRANSFERÊNCIAS:
 *   A planilha pode ter dois formatos de direção:
 *
 *   Formato A (antigo): Remetente = VENDEDOR, Destinatário = COMPRADOR
 *     → jogador está no REMETENTE e vai para o DESTINATÁRIO
 *
 *   Formato B (novo): Remetente = COMPRADOR (paga), Destinatário = VENDEDOR (recebe)
 *     → jogador está no DESTINATÁRIO e vai para o REMETENTE
 *
 *   Como não é possível saber o formato de cada linha, o sistema tenta os dois:
 *   1. Busca o jogador no REMETENTE → se encontrar, transfere para o DESTINATÁRIO
 *   2. Se não encontrar, busca no DESTINATÁRIO → se encontrar, transfere para o REMETENTE
 *      (direção invertida — reportado no resultado)
 *
 * REGRAS DE NEGÓCIO:
 *   REGRA 0 — Time vazio: origem ou destino em branco → ignorado
 *   REGRA 1 — Limite de 30 jogadores por time (rígido)
 *   REGRA 2 — Jogador já envolvido nesta rodada não pode ser transferido de novo
 */
@Service
public class BanTransferService {

  private static final Logger log = LoggerFactory.getLogger(BanTransferService.class);
  private static final int MAX_ROSTER = 30;

  private final TransferClassifierService classifier;
  private final PlayerMatcherService      matcher;
  private final BanFileService            banService;

  public BanTransferService(TransferClassifierService classifier,
                             PlayerMatcherService matcher,
                             BanFileService banService) {
    this.classifier = classifier;
    this.matcher    = matcher;
    this.banService = banService;
  }

  public TransferReport process(List<TransferRecord> records) {
    List<TransferResult> results = new ArrayList<>();
    List<PendingTransfer> pending = buildPendingList(records, results);

    Map<String, Integer> rosterSize           = buildRosterSizeMap();
    Set<String>          transferredThisSession = new HashSet<>();

    int successCount     = 0;
    int notFoundCount    = 0;
    int banMissingCount  = 0;
    int rosterFullCount  = 0;
    int alreadyTxCount   = 0;
    int missingTeamCount = 0;
    int errorCount       = 0;

    for (PendingTransfer pt : pending) {
      if (pt.preResult != null) {
        results.add(pt.preResult);
        if (pt.preResult.getStatus() == TransferResult.Status.SKIPPED_MISSING_TEAM)
          missingTeamCount++;
        continue;
      }

      TransferResult result = executeTransfer(pt, rosterSize, transferredThisSession);
      results.add(result);

      switch (result.getStatus()) {
        case SUCCESS                    -> successCount++;
        case NOT_FOUND                  -> notFoundCount++;
        case BAN_NOT_PROVIDED           -> banMissingCount++;
        case SKIPPED_ROSTER_FULL        -> rosterFullCount++;
        case SKIPPED_PLAYER_TRANSFERRED -> alreadyTxCount++;
        case SKIPPED_MISSING_TEAM       -> missingTeamCount++;
        default                         -> errorCount++;
      }
    }

    int financialSkipped = (int) results.stream()
        .filter(r -> r.getStatus() == TransferResult.Status.SKIPPED_FINANCIAL).count();
    int uncertainSkipped = (int) results.stream()
        .filter(r -> r.getStatus() == TransferResult.Status.SKIPPED_UNCERTAIN).count();

    log.info("=== RELATÓRIO === ok={} notFound={} elenco={} bloqueado={} timevazio={} banAusente={} erros={}",
        successCount, notFoundCount, rosterFullCount, alreadyTxCount,
        missingTeamCount, banMissingCount, errorCount);

    return new TransferReport(
        records.size(), financialSkipped, uncertainSkipped,
        successCount, notFoundCount, banMissingCount, errorCount,
        rosterFullCount, alreadyTxCount, missingTeamCount,
        results, banService.getModifiedTeamNames()
    );
  }

  // ─── Build da lista de pendências ────────────────────────────────────────────

  private List<PendingTransfer> buildPendingList(List<TransferRecord> records,
                                                  List<TransferResult> preResults) {
    List<PendingTransfer> list = new ArrayList<>();

    for (TransferRecord record : records) {
      boolean fromBlank = record.getOrigem().isBlank();
      boolean toBlank   = record.getDestino().isBlank();

      if (fromBlank || toBlank) {
        String norm = StringNormalizer.normalize(record.getMotivo());
        TransferResult.Status status;
        String msg;
        if (classifier.isFinancial(norm) || norm.isBlank()) {
          status = TransferResult.Status.SKIPPED_FINANCIAL;
          msg    = "Transação financeira — ignorada.";
        } else {
          String missing = fromBlank && toBlank ? "origem e destino"
              : fromBlank ? "origem" : "destino";
          status = TransferResult.Status.SKIPPED_MISSING_TEAM;
          msg    = "Campo '" + missing + "' está em branco. Impossível processar.";
        }
        preResults.add(TransferResult.builder()
            .rowIndex(record.getRowIndex())
            .fromTeam(record.getOrigem())
            .toTeam(record.getDestino())
            .rawMotivo(record.getMotivo())
            .status(status).message(msg).build());
        continue;
      }

      String norm = StringNormalizer.normalize(record.getMotivo());
      List<PlayerTransfer> detected = classifier.classify(record);

      if (detected.isEmpty()) {
        boolean isFinancial = classifier.isFinancial(norm);
        preResults.add(TransferResult.builder()
            .rowIndex(record.getRowIndex())
            .fromTeam(record.getOrigem())
            .toTeam(record.getDestino())
            .rawMotivo(record.getMotivo())
            .status(isFinancial
                ? TransferResult.Status.SKIPPED_FINANCIAL
                : TransferResult.Status.SKIPPED_UNCERTAIN)
            .message(isFinancial
                ? "Transação financeira — ignorada."
                : "Motivo ambíguo: '" + record.getMotivo() + "'")
            .build());
        continue;
      }

      for (PlayerTransfer pt : detected) {
        for (String playerName : pt.getPlayerNames()) {
          list.add(new PendingTransfer(record, playerName,
              pt.getFromTeam(), pt.getToTeam()));
        }
      }
    }
    return list;
  }

  // ─── Execução principal ──────────────────────────────────────────────────────

  private TransferResult executeTransfer(PendingTransfer pt,
                                          Map<String, Integer> rosterSize,
                                          Set<String> transferredThisSession) {
    TransferResult.Builder b = TransferResult.builder()
        .rowIndex(pt.record.getRowIndex())
        .playerName(pt.playerName)
        .fromTeam(pt.fromTeam)
        .toTeam(pt.toTeam)
        .rawMotivo(pt.record.getMotivo());

    String playerNorm = StringNormalizer.normalize(pt.playerName);

    // REGRA 2: jogador já envolvido nesta rodada
    if (transferredThisSession.contains(playerNorm)) {
      log.warn("Row {}: '{}' já envolvido nesta rodada",
          pt.record.getRowIndex(), pt.playerName);
      return b.status(TransferResult.Status.SKIPPED_PLAYER_TRANSFERRED)
              .message("Jogador '" + pt.playerName + "' já participou de uma transferência "
                  + "nesta planilha e não pode ser negociado novamente na mesma rodada.")
              .build();
    }

    // Tenta direção normal: fromTeam → toTeam
    TransferResult normalResult = tryTransfer(
        pt, pt.fromTeam, pt.toTeam, false, rosterSize, transferredThisSession);

    if (normalResult.getStatus() == TransferResult.Status.SUCCESS) return normalResult;

    // Se não encontrou no fromTeam (remetente), tenta direção inversa: toTeam → fromTeam
    // Isso cobre o caso onde o remetente é o COMPRADOR e o jogador está no destinatário
    if (normalResult.getStatus() == TransferResult.Status.NOT_FOUND
        || normalResult.getStatus() == TransferResult.Status.BAN_NOT_PROVIDED) {

      TransferResult invertedResult = tryTransfer(
          pt, pt.toTeam, pt.fromTeam, true, rosterSize, transferredThisSession);

      // Só aceita a direção invertida se encontrou o jogador
      if (invertedResult.getStatus() == TransferResult.Status.SUCCESS
          || invertedResult.getStatus() == TransferResult.Status.SKIPPED_ROSTER_FULL) {
        log.info("Row {}: '{}' — direção invertida (jogador estava no destinatário)",
            pt.record.getRowIndex(), pt.playerName);
        return invertedResult;
      }
    }

    // Nenhuma das direções funcionou — retorna o resultado da tentativa normal
    return normalResult;
  }

  // ─── Tenta executar uma transferência em uma direção específica ──────────────

  /**
   * Tenta transferir o jogador de {@code fromTeam} para {@code toTeam}.
   *
   * @param inverted  true = direção invertida (documenta no resultado)
   */
  private TransferResult tryTransfer(PendingTransfer pt,
                                      String fromTeam, String toTeam,
                                      boolean inverted,
                                      Map<String, Integer> rosterSize,
                                      Set<String> transferredThisSession) {
    String playerNorm = StringNormalizer.normalize(pt.playerName);
    String toKey = banService.resolveTeamKey(toTeam);

    // REGRA 1: limite de 30 jogadores no destino
    if (toKey != null) {
      int currentSize = rosterSize.getOrDefault(toKey, 0);
      if (currentSize >= MAX_ROSTER) {
        return TransferResult.builder()
            .rowIndex(pt.record.getRowIndex())
            .playerName(pt.playerName)
            .fromTeam(fromTeam).toTeam(toTeam)
            .rawMotivo(pt.record.getMotivo())
            .status(TransferResult.Status.SKIPPED_ROSTER_FULL)
            .message(String.format(
                "Time '%s' já tem %d jogadores (limite: %d). Precisa vender antes de contratar.",
                toTeam, currentSize, MAX_ROSTER))
            .build();
      }
    }

    // Verifica disponibilidade dos .ban
    if (!banService.hasBan(fromTeam)) {
      return TransferResult.builder()
          .rowIndex(pt.record.getRowIndex())
          .playerName(pt.playerName)
          .fromTeam(fromTeam).toTeam(toTeam)
          .rawMotivo(pt.record.getMotivo())
          .status(TransferResult.Status.BAN_NOT_PROVIDED)
          .message("Arquivo .ban do clube de origem '" + fromTeam + "' não foi enviado.")
          .build();
    }
    if (!banService.hasBan(toTeam)) {
      return TransferResult.builder()
          .rowIndex(pt.record.getRowIndex())
          .playerName(pt.playerName)
          .fromTeam(fromTeam).toTeam(toTeam)
          .rawMotivo(pt.record.getMotivo())
          .status(TransferResult.Status.BAN_NOT_PROVIDED)
          .message("Arquivo .ban do clube de destino '" + toTeam + "' não foi enviado.")
          .build();
    }

    // Busca o jogador
    try {
      List<Object> fromPlayers = banService.getPlayerList(banService.getBan(fromTeam));
      List<Object> toPlayers   = banService.getPlayerList(banService.getBan(toTeam));

      Optional<PlayerMatcherService.MatchResult> matchOpt =
          matcher.findBestMatch(fromPlayers, pt.playerName);

      if (matchOpt.isEmpty()) {
        return TransferResult.builder()
            .rowIndex(pt.record.getRowIndex())
            .playerName(pt.playerName)
            .fromTeam(fromTeam).toTeam(toTeam)
            .rawMotivo(pt.record.getMotivo())
            .status(TransferResult.Status.NOT_FOUND)
            .message("Jogador '" + pt.playerName
                + "' não encontrado no .ban de '" + fromTeam + "'.")
            .build();
      }

      PlayerMatcherService.MatchResult match = matchOpt.get();
      fromPlayers.remove(match.playerObj());
      toPlayers.add(match.playerObj());

      banService.markDirty(fromTeam);
      banService.markDirty(toTeam);

      // Atualiza contadores de elenco
      String fromKey = banService.resolveTeamKey(fromTeam);
      if (fromKey != null) rosterSize.merge(fromKey, -1, Integer::sum);
      if (toKey   != null) rosterSize.merge(toKey,    1, Integer::sum);

      // Bloqueia o jogador para o resto da rodada
      transferredThisSession.add(playerNorm);

      String dirNote = inverted ? " ↔ Direção corrigida: jogador encontrado no time destinatário." : "";
      log.info("Row {}: '{}' {} → {} match='{}' {}%{}",
          pt.record.getRowIndex(), pt.playerName, fromTeam, toTeam,
          match.matchedName(), String.format("%.0f", match.score() * 100),
          inverted ? " [invertido]" : "");

      return TransferResult.builder()
          .rowIndex(pt.record.getRowIndex())
          .playerName(pt.playerName)
          .fromTeam(fromTeam).toTeam(toTeam)
          .rawMotivo(pt.record.getMotivo())
          .status(TransferResult.Status.SUCCESS)
          .matchedName(match.matchedName())
          .matchScore(match.score())
          .message(String.format("Transferido. Nome no .ban: '%s' (similaridade: %.0f%%)%s",
              match.matchedName(), match.score() * 100, dirNote))
          .build();

    } catch (Exception e) {
      log.error("Row {}: erro '{}': {}", pt.record.getRowIndex(), pt.playerName, e.getMessage());
      return TransferResult.builder()
          .rowIndex(pt.record.getRowIndex())
          .playerName(pt.playerName)
          .fromTeam(fromTeam).toTeam(toTeam)
          .rawMotivo(pt.record.getMotivo())
          .status(TransferResult.Status.ERROR)
          .message("Erro inesperado: " + e.getMessage())
          .build();
    }
  }

  // ─── Estado inicial ───────────────────────────────────────────────────────────

  private Map<String, Integer> buildRosterSizeMap() {
    Map<String, Integer> map = new HashMap<>();
    for (String key : banService.loadedBanKeys()) {
      Object ban = banService.getBanByKey(key);
      if (ban != null) {
        int size = banService.getPlayerList(ban).size();
        map.put(key, size);
        log.info("Elenco '{}': {} jogadores", key, size);
      }
    }
    return map;
  }

  // ─── Inner class ─────────────────────────────────────────────────────────────

  private static class PendingTransfer {
    final TransferRecord record;
    final String playerName;
    final String fromTeam;
    final String toTeam;
    TransferResult preResult;

    PendingTransfer(TransferRecord record, String playerName, String fromTeam, String toTeam) {
      this.record = record; this.playerName = playerName;
      this.fromTeam = fromTeam; this.toTeam = toTeam;
    }
  }
}
