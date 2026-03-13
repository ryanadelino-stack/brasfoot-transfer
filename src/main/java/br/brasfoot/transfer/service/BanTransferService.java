package br.brasfoot.transfer.service;

import br.brasfoot.transfer.model.*;
import br.brasfoot.transfer.util.StringNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Orquestra o processo completo de transferência:
 *  1. Classifica cada linha do CSV
 *  2. Para cada jogador detectado, localiza no .ban de origem
 *  3. Remove do origem e insere no destino
 *  4. Acumula os resultados no relatório
 */
@Service
public class BanTransferService {

  private static final Logger log = LoggerFactory.getLogger(BanTransferService.class);

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

  /**
   * Processa todas as linhas do arquivo de transferências.
   * Os .ban já devem ter sido carregados via {@link BanFileService#loadAll}.
   *
   * @param records linhas do arquivo Excel/CSV
   * @return relatório completo com resultados individuais
   */
  public TransferReport process(List<TransferRecord> records) {
    List<TransferResult> results = new ArrayList<>();

    int financialSkipped = 0;
    int uncertainSkipped = 0;
    int successCount     = 0;
    int notFoundCount    = 0;
    int banMissingCount  = 0;
    int errorCount       = 0;

    for (TransferRecord record : records) {
      // 1. Classifica
      List<PlayerTransfer> detected = classifier.classify(record);

      if (detected.isEmpty()) {
        // Decide entre FINANCIAL e UNCERTAIN
        String norm = StringNormalizer.normalize(record.getMotivo());
        TransferResult.Status status;
        String msg;

        if (classifier.isFinancial(norm)) {
          status = TransferResult.Status.SKIPPED_FINANCIAL;
          msg    = "Identificado como transação financeira, não transferência de jogador.";
          financialSkipped++;
        } else {
          status = TransferResult.Status.SKIPPED_UNCERTAIN;
          msg    = "Não foi possível extrair nome de jogador do Motivo: '" + record.getMotivo() + "'";
          uncertainSkipped++;
        }

        results.add(TransferResult.builder()
            .rowIndex(record.getRowIndex())
            .fromTeam(record.getOrigem())
            .toTeam(record.getDestino())
            .rawMotivo(record.getMotivo())
            .status(status)
            .message(msg)
            .build());
        continue;
      }

      // 2. Para cada jogador detectado, executa a transferência
      for (PlayerTransfer pt : detected) {
        for (String playerName : pt.getPlayerNames()) {
          TransferResult result = executeTransfer(record, playerName, pt.getFromTeam(), pt.getToTeam());
          results.add(result);

          switch (result.getStatus()) {
            case SUCCESS          -> successCount++;
            case NOT_FOUND        -> notFoundCount++;
            case BAN_NOT_PROVIDED -> banMissingCount++;
            default               -> errorCount++;
          }
        }
      }
    }

    return new TransferReport(
        records.size(),
        financialSkipped,
        uncertainSkipped,
        successCount,
        notFoundCount,
        banMissingCount,
        errorCount,
        results,
        banService.getModifiedTeamNames()
    );
  }

  // ─── Execução de uma transferência individual ────────────────────────────────

  private TransferResult executeTransfer(TransferRecord record,
                                          String playerName,
                                          String fromTeam,
                                          String toTeam) {
    TransferResult.Builder b = TransferResult.builder()
        .rowIndex(record.getRowIndex())
        .playerName(playerName)
        .fromTeam(fromTeam)
        .toTeam(toTeam)
        .rawMotivo(record.getMotivo());

    // Verifica se os .ban foram carregados
    if (!banService.hasBan(fromTeam)) {
      log.warn("Row {}: .ban não encontrado para o clube de ORIGEM '{}'", record.getRowIndex(), fromTeam);
      return b.status(TransferResult.Status.BAN_NOT_PROVIDED)
              .message("Arquivo .ban do clube de origem '" + fromTeam + "' não foi enviado.")
              .build();
    }

    if (!banService.hasBan(toTeam)) {
      log.warn("Row {}: .ban não encontrado para o clube de DESTINO '{}'", record.getRowIndex(), toTeam);
      return b.status(TransferResult.Status.BAN_NOT_PROVIDED)
              .message("Arquivo .ban do clube de destino '" + toTeam + "' não foi enviado.")
              .build();
    }

    try {
      // Obtém as listas de jogadores
      Object fromBan = banService.getBan(fromTeam);
      Object toBan   = banService.getBan(toTeam);

      List<Object> fromPlayers = banService.getPlayerList(fromBan);
      List<Object> toPlayers   = banService.getPlayerList(toBan);

      // Busca o jogador na lista de origem
      Optional<PlayerMatcherService.MatchResult> matchOpt =
          matcher.findBestMatch(fromPlayers, playerName);

      if (matchOpt.isEmpty()) {
        log.warn("Row {}: jogador '{}' não encontrado em '{}'", record.getRowIndex(), playerName, fromTeam);
        return b.status(TransferResult.Status.NOT_FOUND)
                .message("Jogador '" + playerName + "' não encontrado no .ban de '"
                    + fromTeam + "'. Verifique o nome no arquivo .ban.")
                .build();
      }

      PlayerMatcherService.MatchResult match = matchOpt.get();
      Object playerObj = match.playerObj();

      // Remove de origem e adiciona em destino
      fromPlayers.remove(playerObj);
      toPlayers.add(playerObj);

      // Marca ambos os times como modificados
      banService.markDirty(fromTeam);
      banService.markDirty(toTeam);

      log.info("Row {}: '{}' (match='{}', score={}%) transferido de '{}' para '{}'",
          record.getRowIndex(), playerName, match.matchedName(),
          String.format("%.0f", match.score() * 100), fromTeam, toTeam);

      return b.status(TransferResult.Status.SUCCESS)
              .matchedName(match.matchedName())
              .matchScore(match.score())
              .message(String.format("Transferido com sucesso. Nome no .ban: '%s' (similaridade: %.0f%%)",
                  match.matchedName(), match.score() * 100))
              .build();

    } catch (Exception e) {
      log.error("Row {}: erro inesperado ao transferir '{}': {}", record.getRowIndex(), playerName, e.getMessage(), e);
      return b.status(TransferResult.Status.ERROR)
              .message("Erro inesperado: " + e.getMessage())
              .build();
    }
  }
}
