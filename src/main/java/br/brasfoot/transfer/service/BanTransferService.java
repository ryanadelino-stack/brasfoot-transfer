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
 * DIREÇÃO FIXA (Formato B):
 *   Col C (Remetente)    = time que VAI RECEBER o jogador (destino)
 *   Col F (Destinatário) = time de onde o jogador SAI (origem)
 *
 * REGRAS DE NEGÓCIO:
 *   REGRA 0 — Time vazio: origem ou destino em branco → ignorado
 *   REGRA 1 — Elenco cheio com lookahead inteligente:
 *              se o time estiver cheio mas tiver vendas futuras que abrem vaga
 *              antes da próxima compra, a transferência é permitida.
 *   REGRA 2 — Jogador já envolvido nesta rodada não pode ser transferido de novo.
 *   REGRA 3 — Dispensa: "remover X" → remove X do elenco sem transferir para nenhum time.
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
    List<TransferResult>  results = new ArrayList<>();
    List<PendingTransfer> pending = buildPendingList(records, results);

    // rosterSize: teamKey → quantidade atual de jogadores (atualizada dinamicamente)
    Map<String, Integer> rosterSize            = buildRosterSizeMap();
    // jogadores envolvidos em qualquer transferência bem-sucedida nesta rodada
    Set<String>          transferredThisSession = new HashSet<>();

    int successCount     = 0;
    int notFoundCount    = 0;
    int banMissingCount  = 0;
    int rosterFullCount  = 0;
    int alreadyTxCount   = 0;
    int missingTeamCount = 0;
    int dismissedCount   = 0;
    int errorCount       = 0;

    for (int i = 0; i < pending.size(); i++) {
      PendingTransfer pt = pending.get(i);

      if (pt.preResult != null) {
        results.add(pt.preResult);
        if (pt.preResult.getStatus() == TransferResult.Status.SKIPPED_MISSING_TEAM)
          missingTeamCount++;
        continue;
      }

      TransferResult result;
      if (pt.isDismissal) {
        result = executeDismissal(pt, rosterSize, transferredThisSession);
      } else {
        result = executeTransfer(pt, i, pending, rosterSize, transferredThisSession);
      }

      results.add(result);

      switch (result.getStatus()) {
        case SUCCESS                    -> successCount++;
        case NOT_FOUND                  -> notFoundCount++;
        case BAN_NOT_PROVIDED           -> banMissingCount++;
        case SKIPPED_ROSTER_FULL        -> rosterFullCount++;
        case SKIPPED_PLAYER_TRANSFERRED -> alreadyTxCount++;
        case SKIPPED_MISSING_TEAM       -> missingTeamCount++;
        case DISMISSED                  -> dismissedCount++;
        default                         -> errorCount++;
      }
    }

    int financialSkipped = (int) results.stream()
        .filter(r -> r.getStatus() == TransferResult.Status.SKIPPED_FINANCIAL).count();
    int uncertainSkipped = (int) results.stream()
        .filter(r -> r.getStatus() == TransferResult.Status.SKIPPED_UNCERTAIN).count();

    log.info("=== RELATÓRIO === ok={} dispensados={} notFound={} elenco={} bloqueado={} timevazio={} banAusente={} erros={}",
        successCount, dismissedCount, notFoundCount, rosterFullCount, alreadyTxCount,
        missingTeamCount, banMissingCount, errorCount);

    return new TransferReport(
        records.size(), financialSkipped, uncertainSkipped,
        successCount, notFoundCount, banMissingCount, errorCount,
        rosterFullCount, alreadyTxCount, missingTeamCount, dismissedCount,
        results, banService.getModifiedTeamNames()
    );
  }

  // ─── Build da lista de pendências ────────────────────────────────────────────

  private List<PendingTransfer> buildPendingList(List<TransferRecord> records,
                                                  List<TransferResult> preResults) {
    List<PendingTransfer> list = new ArrayList<>();

    for (TransferRecord record : records) {
      String motivo = record.getMotivo();

      // DIREÇÃO FIXA: Destinatário (F) = origem do jogador, Remetente (C) = destino
      // O ExcelParserService já lê C como "origem" e F como "destino" no Formato A.
      // No Formato B (ponto-e-vírgula), C=Remetente e F=Destinatário.
      // Com a lógica fixa: fromTeam = getDestino() (quem vende), toTeam = getOrigem() (quem compra)
      String fromTeam = record.getDestino(); // Col F: de onde o jogador SAI
      String toTeam   = record.getOrigem();  // Col C: para onde o jogador VAI

      // REGRA 3: dispensa — detectar antes de verificar times vazios
      String dismissedName = classifier.extractDismissedPlayerName(motivo);
      if (dismissedName != null) {
        // Na dispensa, o jogador está no time que gerou a transação.
        // Pode estar em fromTeam ou toTeam — vamos tentar os dois ao executar.
        // fromTeam = de onde tirar (se preenchido)
        String teamWithPlayer = !fromTeam.isBlank() ? fromTeam
            : !toTeam.isBlank() ? toTeam : "";
        if (teamWithPlayer.isBlank()) {
          preResults.add(TransferResult.builder()
              .rowIndex(record.getRowIndex())
              .fromTeam(fromTeam).toTeam(toTeam).rawMotivo(motivo)
              .status(TransferResult.Status.SKIPPED_MISSING_TEAM)
              .message("Dispensa ignorada: nenhum time informado.")
              .build());
        } else {
          PendingTransfer pt = new PendingTransfer(record, dismissedName, teamWithPlayer, "", true);
          list.add(pt);
        }
        continue;
      }

      // REGRA 0: time vazio
      boolean fromBlank = fromTeam.isBlank();
      boolean toBlank   = toTeam.isBlank();

      if (fromBlank || toBlank) {
        String norm = StringNormalizer.normalize(motivo);
        TransferResult.Status status;
        String msg;
        if (classifier.isFinancial(norm) || norm.isBlank()) {
          status = TransferResult.Status.SKIPPED_FINANCIAL;
          msg    = "Transação financeira — ignorada.";
        } else {
          String missing = fromBlank && toBlank ? "origem e destino"
              : fromBlank ? "time vendedor" : "time comprador";
          status = TransferResult.Status.SKIPPED_MISSING_TEAM;
          msg    = "Campo '" + missing + "' está em branco. Impossível processar.";
        }
        preResults.add(TransferResult.builder()
            .rowIndex(record.getRowIndex())
            .fromTeam(fromTeam).toTeam(toTeam).rawMotivo(motivo)
            .status(status).message(msg).build());
        continue;
      }

      // Classifica normalmente
      String norm = StringNormalizer.normalize(motivo);
      List<PlayerTransfer> detected = classifier.classify(record);

      if (detected.isEmpty()) {
        boolean isFinancial = classifier.isFinancial(norm);
        preResults.add(TransferResult.builder()
            .rowIndex(record.getRowIndex())
            .fromTeam(fromTeam).toTeam(toTeam).rawMotivo(motivo)
            .status(isFinancial
                ? TransferResult.Status.SKIPPED_FINANCIAL
                : TransferResult.Status.SKIPPED_UNCERTAIN)
            .message(isFinancial ? "Transação financeira — ignorada."
                : "Motivo ambíguo: '" + motivo + "'")
            .build());
        continue;
      }

      for (PlayerTransfer pt : detected) {
        for (String playerName : pt.getPlayerNames()) {
          // Usa a direção fixa: fromTeam = col F (destinatário/vendedor), toTeam = col C (remetente/comprador)
          list.add(new PendingTransfer(record, playerName, fromTeam, toTeam, false));
        }
      }
    }
    return list;
  }

  // ─── Execução de dispensa ────────────────────────────────────────────────────

  private TransferResult executeDismissal(PendingTransfer pt,
                                           Map<String, Integer> rosterSize,
                                           Set<String> transferredThisSession) {
    TransferResult.Builder b = TransferResult.builder()
        .rowIndex(pt.record.getRowIndex())
        .playerName(pt.playerName)
        .fromTeam(pt.fromTeam).toTeam("")
        .rawMotivo(pt.record.getMotivo());

    String playerNorm = StringNormalizer.normalize(pt.playerName);

    String fromKey0d    = banService.resolveTeamKey(pt.fromTeam);
    String blockingKeyD = playerNorm + "|" + (fromKey0d != null ? fromKey0d : pt.fromTeam);
    if (transferredThisSession.contains(blockingKeyD)) {
      return b.status(TransferResult.Status.SKIPPED_PLAYER_TRANSFERRED)
              .message("Jogador '" + pt.playerName + "' já foi envolvido nesta rodada.")
              .build();
    }

    if (!banService.hasBan(pt.fromTeam)) {
      return b.status(TransferResult.Status.BAN_NOT_PROVIDED)
              .message("Arquivo .ban de '" + pt.fromTeam + "' não foi enviado.")
              .build();
    }

    try {
      List<Object> fromPlayers = banService.getPlayerList(banService.getBan(pt.fromTeam));
      Optional<PlayerMatcherService.MatchResult> matchOpt =
          matcher.findBestMatch(fromPlayers, pt.playerName);

      if (matchOpt.isEmpty()) {
        return b.status(TransferResult.Status.NOT_FOUND)
                .message("Jogador '" + pt.playerName + "' não encontrado no .ban de '" + pt.fromTeam + "'.")
                .build();
      }

      PlayerMatcherService.MatchResult match = matchOpt.get();
      fromPlayers.remove(match.playerObj());
      banService.markDirty(pt.fromTeam);

      String fromKey = banService.resolveTeamKey(pt.fromTeam);
      if (fromKey != null) rosterSize.merge(fromKey, -1, Integer::sum);
      String fromKeyD2 = banService.resolveTeamKey(pt.fromTeam);
      transferredThisSession.add(playerNorm + "|" + (fromKeyD2 != null ? fromKeyD2 : pt.fromTeam));

      log.info("Row {}: '{}' DISPENSADO de '{}' (match='{}')",
          pt.record.getRowIndex(), pt.playerName, pt.fromTeam, match.matchedName());

      return b.status(TransferResult.Status.DISMISSED)
              .matchedName(match.matchedName())
              .matchScore(match.score())
              .message(String.format("Jogador '%s' dispensado de '%s'. Nome no .ban: '%s'.",
                  pt.playerName, pt.fromTeam, match.matchedName()))
              .build();

    } catch (Exception e) {
      return b.status(TransferResult.Status.ERROR).message("Erro: " + e.getMessage()).build();
    }
  }

  // ─── Execução de transferência ───────────────────────────────────────────────

  private TransferResult executeTransfer(PendingTransfer pt,
                                          int currentIdx,
                                          List<PendingTransfer> allPending,
                                          Map<String, Integer> rosterSize,
                                          Set<String> transferredThisSession) {
    TransferResult.Builder b = TransferResult.builder()
        .rowIndex(pt.record.getRowIndex())
        .playerName(pt.playerName)
        .fromTeam(pt.fromTeam).toTeam(pt.toTeam)
        .rawMotivo(pt.record.getMotivo());

    String playerNorm = StringNormalizer.normalize(pt.playerName);
    String toKey      = banService.resolveTeamKey(pt.toTeam);

    // REGRA 2: mesmo jogador do mesmo time de origem já transferido nesta rodada.
    // Chave: playerNorm|fromTeamKey — permite homônimos em clubes diferentes.
    String fromKey0    = banService.resolveTeamKey(pt.fromTeam);
    String blockingKey = playerNorm + "|" + (fromKey0 != null ? fromKey0 : pt.fromTeam);
    if (transferredThisSession.contains(blockingKey)) {
      return b.status(TransferResult.Status.SKIPPED_PLAYER_TRANSFERRED)
              .message("Transferência duplicada: '" + pt.playerName + "' já foi transferido "
                  + "desta equipe nesta planilha.")
              .build();
    }

    // REGRA 1: limite de 30 jogadores com lookahead inteligente
    if (toKey != null) {
      int currentSize = rosterSize.getOrDefault(toKey, 0);
      if (currentSize >= MAX_ROSTER) {
        // Verifica se há vendas futuras que abrem vaga antes da próxima compra
        if (!hasFutureSpaceFor(pt.toTeam, toKey, currentIdx, allPending, rosterSize)) {
          return b.status(TransferResult.Status.SKIPPED_ROSTER_FULL)
                  .message(String.format(
                      "Time '%s' está com %d jogadores (limite: %d) e não há vendas "
                          + "previstas nesta planilha que abram vaga.",
                      pt.toTeam, currentSize, MAX_ROSTER))
                  .build();
        }
        log.info("Row {}: '{}' cheio ({} jog) mas tem venda futura — permitindo",
            pt.record.getRowIndex(), pt.toTeam, currentSize);
      }
    }

    // Verifica .ban
    if (!banService.hasBan(pt.fromTeam)) {
      return b.status(TransferResult.Status.BAN_NOT_PROVIDED)
              .message("Arquivo .ban de '" + pt.fromTeam + "' não foi enviado.")
              .build();
    }
    if (!banService.hasBan(pt.toTeam)) {
      return b.status(TransferResult.Status.BAN_NOT_PROVIDED)
              .message("Arquivo .ban de '" + pt.toTeam + "' não foi enviado.")
              .build();
    }

    // Busca e move o jogador
    try {
      List<Object> fromPlayers = banService.getPlayerList(banService.getBan(pt.fromTeam));
      List<Object> toPlayers   = banService.getPlayerList(banService.getBan(pt.toTeam));

      Optional<PlayerMatcherService.MatchResult> matchOpt =
          matcher.findBestMatch(fromPlayers, pt.playerName);

      if (matchOpt.isEmpty()) {
        return b.status(TransferResult.Status.NOT_FOUND)
                .message("Jogador '" + pt.playerName
                    + "' não encontrado no .ban de '" + pt.fromTeam + "'.")
                .build();
      }

      PlayerMatcherService.MatchResult match = matchOpt.get();
      fromPlayers.remove(match.playerObj());
      toPlayers.add(match.playerObj());

      banService.markDirty(pt.fromTeam);
      banService.markDirty(pt.toTeam);

      String fromKey = banService.resolveTeamKey(pt.fromTeam);
      if (fromKey != null) rosterSize.merge(fromKey, -1, Integer::sum);
      if (toKey   != null) rosterSize.merge(toKey,    1, Integer::sum);
      String fromKeyAdd = banService.resolveTeamKey(pt.fromTeam);
      transferredThisSession.add(playerNorm + "|" + (fromKeyAdd != null ? fromKeyAdd : pt.fromTeam));

      log.info("Row {}: '{}' {} → {} match='{}' {}%",
          pt.record.getRowIndex(), pt.playerName, pt.fromTeam, pt.toTeam,
          match.matchedName(), String.format("%.0f", match.score() * 100));

      return b.status(TransferResult.Status.SUCCESS)
              .matchedName(match.matchedName())
              .matchScore(match.score())
              .message(String.format("Transferido. Nome no .ban: '%s' (similaridade: %.0f%%)",
                  match.matchedName(), match.score() * 100))
              .build();

    } catch (Exception e) {
      log.error("Row {}: erro '{}': {}", pt.record.getRowIndex(), pt.playerName, e.getMessage());
      return b.status(TransferResult.Status.ERROR)
              .message("Erro inesperado: " + e.getMessage())
              .build();
    }
  }

  // ─── Lookahead: verifica se o time terá vaga antes de outra compra ───────────

  /**
   * Simula o elenco do time destino a partir da linha atual.
   * Retorna true se, antes de uma nova compra que deixaria o elenco em 31+,
   * há uma venda que abre vaga (≤30 jogadores).
   */
  private boolean hasFutureSpaceFor(String toTeam, String toKey,
                                     int currentIdx,
                                     List<PendingTransfer> allPending,
                                     Map<String, Integer> rosterSize) {
    if (toKey == null) return false;
    int simSize = rosterSize.getOrDefault(toKey, 0);

    for (int i = currentIdx + 1; i < allPending.size(); i++) {
      PendingTransfer future = allPending.get(i);
      if (future.preResult != null) continue;
      if (future.isDismissal) {
        // Dispensa futura do mesmo time também abre vaga
        String dismissKey = banService.resolveTeamKey(future.fromTeam);
        if (toKey.equals(dismissKey)) { simSize--; if (simSize < MAX_ROSTER) return true; }
        continue;
      }

      String futureFromKey = banService.resolveTeamKey(future.fromTeam);
      String futureToKey   = banService.resolveTeamKey(future.toTeam);

      // Venda futura → abre vaga
      if (toKey.equals(futureFromKey)) {
        simSize--;
        if (simSize < MAX_ROSTER) return true;
      }
      // Compra futura → ocupa vaga
      else if (toKey.equals(futureToKey)) {
        simSize++;
      }
    }
    return false;
  }

  // ─── Estado inicial ───────────────────────────────────────────────────────────

  private Map<String, Integer> buildRosterSizeMap() {
    Map<String, Integer> map = new HashMap<>();
    for (String key : banService.loadedBanKeys()) {
      Object ban = banService.getBanByKey(key);
      if (ban != null) {
        int size = banService.getPlayerList(ban).size();
        map.put(key, size);
        log.debug("Elenco '{}': {} jogadores", key, size);
      }
    }
    return map;
  }

  // ─── Inner class ─────────────────────────────────────────────────────────────

  private static class PendingTransfer {
    final TransferRecord record;
    final String         playerName;
    final String         fromTeam;
    final String         toTeam;
    final boolean        isDismissal;  // true = dispensa (sem toTeam)
    TransferResult       preResult;

    PendingTransfer(TransferRecord record, String playerName,
                    String fromTeam, String toTeam, boolean isDismissal) {
      this.record      = record;
      this.playerName  = playerName;
      this.fromTeam    = fromTeam;
      this.toTeam      = toTeam;
      this.isDismissal = isDismissal;
    }
  }
}
