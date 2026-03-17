package br.brasfoot.transfer.service;

import br.brasfoot.transfer.model.*;
import br.brasfoot.transfer.util.StringNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Orquestra o processo completo de transferência com 3 regras de negócio:
 *
 *  REGRA 1 — Limite de 30 jogadores (rígido)
 *    O time destino NUNCA pode ultrapassar 30 atletas. Se já tem 30 no momento
 *    da transferência, ela é bloqueada. Simples, sem lookahead.
 *
 *  REGRA 2 — Anti-dupla-venda
 *    Um jogador que SAIU de um time nesta planilha não pode ser vendido
 *    novamente (ele já não pertence mais ao time de origem).
 *
 *  REGRA 3 — Jogador recém-chegado não pode ser revendido
 *    Um jogador que CHEGOU a um time nesta planilha não pode ser vendido
 *    na mesma planilha. Só poderá ser negociado em uma rodada futura.
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

  // ─── Entrada principal ───────────────────────────────────────────────────────

  public TransferReport process(List<TransferRecord> records) {
    List<TransferResult> results = new ArrayList<>();

    // Passo 1: classifica todas as linhas e expande em transferências individuais
    List<PendingTransfer> pending = buildPendingList(records, results);

    // Passo 2: estado de simulação em memória
    // rosterSize:         teamKey → quantidade atual de jogadores no elenco
    // playerCurrentTeam:  playerNorm → teamKey onde o jogador está agora
    // arrivedThisSession: playerNorm dos jogadores que CHEGARAM nesta planilha
    //                     (esses não podem ser revendidos na mesma rodada)
    Map<String, Integer> rosterSize         = buildRosterSizeMap();
    Map<String, String>  playerCurrentTeam  = buildPlayerTeamMap();
    Set<String>          arrivedThisSession = new HashSet<>();

    // Passo 3: processa cada transferência em ordem
    int successCount    = 0;
    int notFoundCount   = 0;
    int banMissingCount = 0;
    int rosterFullCount = 0;
    int alreadyTxCount  = 0;
    int errorCount      = 0;

    for (PendingTransfer pt : pending) {
      if (pt.preResult != null) {
        results.add(pt.preResult);
        continue;
      }

      TransferResult result = executeTransfer(
          pt, rosterSize, playerCurrentTeam, arrivedThisSession);
      results.add(result);

      switch (result.getStatus()) {
        case SUCCESS                    -> successCount++;
        case NOT_FOUND                  -> notFoundCount++;
        case BAN_NOT_PROVIDED           -> banMissingCount++;
        case SKIPPED_ROSTER_FULL        -> rosterFullCount++;
        case SKIPPED_PLAYER_TRANSFERRED -> alreadyTxCount++;
        default                         -> errorCount++;
      }
    }

    int financialSkipped = (int) results.stream()
        .filter(r -> r.getStatus() == TransferResult.Status.SKIPPED_FINANCIAL).count();
    int uncertainSkipped = (int) results.stream()
        .filter(r -> r.getStatus() == TransferResult.Status.SKIPPED_UNCERTAIN).count();

    log.info("=== RELATÓRIO FINAL === transferidos={} naoEncontrados={} elenco={} revendidos={} banAusente={} erros={}",
        successCount, notFoundCount, rosterFullCount, alreadyTxCount, banMissingCount, errorCount);

    return new TransferReport(
        records.size(), financialSkipped, uncertainSkipped,
        successCount, notFoundCount, banMissingCount, errorCount,
        rosterFullCount, alreadyTxCount,
        results, banService.getModifiedTeamNames()
    );
  }

  // ─── Build da lista de pendências ────────────────────────────────────────────

  private List<PendingTransfer> buildPendingList(List<TransferRecord> records,
                                                  List<TransferResult> preResults) {
    List<PendingTransfer> list = new ArrayList<>();

    for (TransferRecord record : records) {
      String norm = StringNormalizer.normalize(record.getMotivo());
      List<PlayerTransfer> detected = classifier.classify(record);

      if (detected.isEmpty()) {
        boolean isFinancial = classifier.isFinancial(norm);
        TransferResult r = TransferResult.builder()
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
            .build();
        preResults.add(r);
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

  // ─── Execução de uma transferência individual ────────────────────────────────

  private TransferResult executeTransfer(PendingTransfer pt,
                                          Map<String, Integer> rosterSize,
                                          Map<String, String> playerCurrentTeam,
                                          Set<String> arrivedThisSession) {
    TransferResult.Builder b = TransferResult.builder()
        .rowIndex(pt.record.getRowIndex())
        .playerName(pt.playerName)
        .fromTeam(pt.fromTeam)
        .toTeam(pt.toTeam)
        .rawMotivo(pt.record.getMotivo());

    String playerNorm = StringNormalizer.normalize(pt.playerName);
    String fromKey    = banService.resolveTeamKey(pt.fromTeam);
    String toKey      = banService.resolveTeamKey(pt.toTeam);

    // ── REGRA 3: Jogador recém-chegado não pode ser revendido ─────────────────
    if (arrivedThisSession.contains(playerNorm)) {
      log.warn("Row {}: '{}' chegou nesta rodada e não pode ser revendido",
          pt.record.getRowIndex(), pt.playerName);
      return b.status(TransferResult.Status.SKIPPED_PLAYER_TRANSFERRED)
              .message("Jogador '" + pt.playerName + "' foi contratado nesta mesma planilha "
                  + "e não pode ser revendido na mesma rodada.")
              .build();
    }

    // ── REGRA 2: Anti-dupla-venda ─────────────────────────────────────────────
    // Se o jogador está rastreado e o time atual é diferente do fromTeam,
    // ele já foi vendido anteriormente nesta planilha
    if (fromKey != null && playerCurrentTeam.containsKey(playerNorm)) {
      String currentKey = playerCurrentTeam.get(playerNorm);
      if (!currentKey.equals(fromKey)) {
        log.warn("Row {}: '{}' já foi transferido nesta rodada (está em '{}')",
            pt.record.getRowIndex(), pt.playerName, currentKey);
        return b.status(TransferResult.Status.SKIPPED_PLAYER_TRANSFERRED)
                .message("Jogador '" + pt.playerName + "' já foi transferido anteriormente "
                    + "nesta planilha. A primeira transferência é mantida.")
                .build();
      }
    }

    // ── REGRA 1: Limite rígido de 30 jogadores ────────────────────────────────
    if (toKey != null) {
      int currentSize = rosterSize.getOrDefault(toKey, 0);
      if (currentSize >= MAX_ROSTER) {
        log.warn("Row {}: time '{}' com {} jogadores — limite de {} atingido",
            pt.record.getRowIndex(), pt.toTeam, currentSize, MAX_ROSTER);
        return b.status(TransferResult.Status.SKIPPED_ROSTER_FULL)
                .message(String.format(
                    "Time '%s' já tem %d jogadores (limite máximo: %d). "
                        + "É necessário que o time venda um jogador antes de contratar.",
                    pt.toTeam, currentSize, MAX_ROSTER))
                .build();
      }
    }

    // ── Verificação de .ban disponível ────────────────────────────────────────
    if (!banService.hasBan(pt.fromTeam)) {
      return b.status(TransferResult.Status.BAN_NOT_PROVIDED)
              .message("Arquivo .ban do clube de origem '" + pt.fromTeam + "' não foi enviado.")
              .build();
    }
    if (!banService.hasBan(pt.toTeam)) {
      return b.status(TransferResult.Status.BAN_NOT_PROVIDED)
              .message("Arquivo .ban do clube de destino '" + pt.toTeam + "' não foi enviado.")
              .build();
    }

    // ── Busca o jogador e executa a transferência ─────────────────────────────
    try {
      List<Object> fromPlayers = banService.getPlayerList(banService.getBan(pt.fromTeam));
      List<Object> toPlayers   = banService.getPlayerList(banService.getBan(pt.toTeam));

      Optional<PlayerMatcherService.MatchResult> matchOpt =
          matcher.findBestMatch(fromPlayers, pt.playerName);

      if (matchOpt.isEmpty()) {
        log.warn("Row {}: '{}' não encontrado no .ban de '{}'",
            pt.record.getRowIndex(), pt.playerName, pt.fromTeam);
        return b.status(TransferResult.Status.NOT_FOUND)
                .message("Jogador '" + pt.playerName
                    + "' não encontrado no .ban de '" + pt.fromTeam + "'.")
                .build();
      }

      PlayerMatcherService.MatchResult match = matchOpt.get();

      // Move fisicamente o jogador entre as listas
      fromPlayers.remove(match.playerObj());
      toPlayers.add(match.playerObj());

      banService.markDirty(pt.fromTeam);
      banService.markDirty(pt.toTeam);

      // Atualiza contadores de elenco
      if (fromKey != null) rosterSize.merge(fromKey, -1, Integer::sum);
      if (toKey   != null) rosterSize.merge(toKey,    1, Integer::sum);

      // Atualiza localização do jogador (anti-dupla-venda)
      if (toKey != null) {
        playerCurrentTeam.put(playerNorm, toKey);
        // Marca como recém-chegado (não pode ser revendido nesta rodada)
        arrivedThisSession.add(playerNorm);
      }

      log.info("Row {}: '{}' → '{}' (match='{}' {}%)",
          pt.record.getRowIndex(), pt.fromTeam, pt.toTeam,
          match.matchedName(), String.format("%.0f", match.score() * 100));

      return b.status(TransferResult.Status.SUCCESS)
              .matchedName(match.matchedName())
              .matchScore(match.score())
              .message(String.format("Transferido. Nome no .ban: '%s' (similaridade: %.0f%%)",
                  match.matchedName(), match.score() * 100))
              .build();

    } catch (Exception e) {
      log.error("Row {}: erro ao transferir '{}': {}",
          pt.record.getRowIndex(), pt.playerName, e.getMessage(), e);
      return b.status(TransferResult.Status.ERROR)
              .message("Erro inesperado: " + e.getMessage())
              .build();
    }
  }

  // ─── Inicialização do estado de simulação ────────────────────────────────────

  private Map<String, Integer> buildRosterSizeMap() {
    Map<String, Integer> map = new HashMap<>();
    for (String key : banService.loadedBanKeys()) {
      Object ban = banService.getBanByKey(key);
      if (ban != null) {
        int size = banService.getPlayerList(ban).size();
        map.put(key, size);
        log.info("Elenco carregado — '{}': {} jogadores", key, size);
      }
    }
    return map;
  }

  private Map<String, String> buildPlayerTeamMap() {
    Map<String, String> map = new HashMap<>();
    for (String key : banService.loadedBanKeys()) {
      Object ban = banService.getBanByKey(key);
      if (ban == null) continue;
      for (Object player : banService.getPlayerList(ban)) {
        String name = matcher.getPlayerName(player);
        if (name != null && !name.isBlank()) {
          map.put(StringNormalizer.normalize(name), key);
        }
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
      this.record     = record;
      this.playerName = playerName;
      this.fromTeam   = fromTeam;
      this.toTeam     = toTeam;
    }
  }
}
