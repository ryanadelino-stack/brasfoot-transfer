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
 *  REGRA 0 — Time vazio (SKIPPED_MISSING_TEAM)
 *    Se origem OU destino estiver em branco, a transferência não pode acontecer.
 *
 *  REGRA 1 — Limite de 30 jogadores (rígido)
 *    O time destino nunca pode ultrapassar 30 atletas no momento da transferência.
 *
 *  REGRA 2 — Jogador já envolvido nesta rodada (anti-dupla-venda + anti-revenda)
 *    Se um jogador participou de qualquer transferência bem-sucedida nesta planilha
 *    (saiu OU chegou), ele está bloqueado para o resto da rodada.
 *    Isso cobre dois casos:
 *      a) Dupla venda: o Flamengo tenta vender Pedro para dois times diferentes.
 *      b) Revenda imediata: o Palmeiras comprou Pedro e tenta revendê-lo na mesma rodada.
 *
 *  NOTA IMPORTANTE sobre buildRosterSizeMap:
 *    Usamos apenas os .ban fornecidos para calcular tamanho de elenco.
 *    NÃO pré-populamos um mapa de "onde está cada jogador" a partir dos .ban,
 *    pois isso causaria falsos positivos quando jogadores aparecem em múltiplos
 *    arquivos .ban ou quando o .ban reflete um estado diferente da planilha.
 *    Quem decide se o jogador pode sair de um time é o matcher (NOT_FOUND se não achar).
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

    // rosterSize: teamKey → quantidade atual de jogadores (atualizada dinamicamente)
    Map<String, Integer> rosterSize = buildRosterSizeMap();

    // transferredThisSession: nomes normalizados de jogadores que já participaram
    // de qualquer transferência BEM-SUCEDIDA nesta rodada (saída OU chegada).
    // Evita tanto dupla-venda quanto revenda imediata.
    Set<String> transferredThisSession = new HashSet<>();

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

      // REGRA 0: time vazio — verificar antes de classificar
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
          msg    = "Campo '" + missing + "' está em branco. Impossível processar sem ambos os times.";
        }

        preResults.add(TransferResult.builder()
            .rowIndex(record.getRowIndex())
            .fromTeam(record.getOrigem())
            .toTeam(record.getDestino())
            .rawMotivo(record.getMotivo())
            .status(status)
            .message(msg)
            .build());
        continue;
      }

      // Classifica normalmente
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

  // ─── Execução de uma transferência individual ────────────────────────────────

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
    String toKey      = banService.resolveTeamKey(pt.toTeam);

    // REGRA 2: jogador já envolvido em transferência nesta rodada
    if (transferredThisSession.contains(playerNorm)) {
      log.warn("Row {}: '{}' já foi transferido nesta rodada",
          pt.record.getRowIndex(), pt.playerName);
      return b.status(TransferResult.Status.SKIPPED_PLAYER_TRANSFERRED)
              .message("Jogador '" + pt.playerName + "' já participou de uma transferência "
                  + "nesta planilha (saída ou chegada) e não pode ser negociado novamente "
                  + "na mesma rodada.")
              .build();
    }

    // REGRA 1: limite rígido de 30 jogadores
    if (toKey != null) {
      int currentSize = rosterSize.getOrDefault(toKey, 0);
      if (currentSize >= MAX_ROSTER) {
        log.warn("Row {}: '{}' com {} jogadores — limite atingido",
            pt.record.getRowIndex(), pt.toTeam, currentSize);
        return b.status(TransferResult.Status.SKIPPED_ROSTER_FULL)
                .message(String.format(
                    "Time '%s' já tem %d jogadores (limite: %d). "
                        + "O time precisa vender um jogador antes de contratar.",
                    pt.toTeam, currentSize, MAX_ROSTER))
                .build();
      }
    }

    // Verificação de .ban disponível
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

    // Busca e move o jogador
    try {
      List<Object> fromPlayers = banService.getPlayerList(banService.getBan(pt.fromTeam));
      List<Object> toPlayers   = banService.getPlayerList(banService.getBan(pt.toTeam));

      Optional<PlayerMatcherService.MatchResult> matchOpt =
          matcher.findBestMatch(fromPlayers, pt.playerName);

      if (matchOpt.isEmpty()) {
        log.warn("Row {}: '{}' não encontrado em '{}'",
            pt.record.getRowIndex(), pt.playerName, pt.fromTeam);
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

      // Atualiza contadores de elenco
      String fromKey = banService.resolveTeamKey(pt.fromTeam);
      if (fromKey != null) rosterSize.merge(fromKey, -1, Integer::sum);
      if (toKey   != null) rosterSize.merge(toKey,    1, Integer::sum);

      // Bloqueia o jogador para o resto da rodada
      transferredThisSession.add(playerNorm);

      log.info("Row {}: '{}' → '{}' match='{}' {}%",
          pt.record.getRowIndex(), pt.fromTeam, pt.toTeam,
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

  // ─── Estado inicial ───────────────────────────────────────────────────────────

  /**
   * Constrói o mapa de tamanhos de elenco a partir dos .ban carregados.
   * Usado APENAS para verificar o limite de 30 jogadores.
   * NÃO é usado para rastrear onde cada jogador está — isso causaria falsos positivos.
   */
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
