package br.brasfoot.transfer.service;

import br.brasfoot.transfer.model.AnalysisReport;
import br.brasfoot.transfer.model.AnalysisReport.AnalyzedTransfer;
import br.brasfoot.transfer.model.PlayerTransfer;
import br.brasfoot.transfer.model.TransferRecord;
import br.brasfoot.transfer.util.StringNormalizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Analisa o arquivo de transferências SEM precisar dos arquivos .ban.
 *
 * Retorna:
 *  - Quais times estão envolvidos (origem + destino das transferências de jogadores)
 *  - Prévia de cada linha classificada
 *  - Contadores de financeiros/incertos ignorados
 */
@Service
public class TransferAnalysisService {

  private final TransferClassifierService classifier;

  public TransferAnalysisService(TransferClassifierService classifier) {
    this.classifier = classifier;
  }

  public AnalysisReport analyze(List<TransferRecord> records) {
    List<AnalyzedTransfer> transfers = new ArrayList<>();
    Set<String> teamsInvolved = new LinkedHashSet<>(); // mantém ordem de aparição

    int playerCount    = 0;
    int financialCount = 0;
    int uncertainCount = 0;

    for (TransferRecord record : records) {
      String norm = StringNormalizer.normalize(record.getMotivo());

      // Classifica a linha
      List<PlayerTransfer> detected = classifier.classify(record);

      if (detected.isEmpty()) {
        // Financeiro ou incerto
        boolean isFinancial = classifier.isFinancial(norm);
        String type = isFinancial ? "FINANCIAL" : "UNCERTAIN";

        if (isFinancial) financialCount++;
        else             uncertainCount++;

        transfers.add(new AnalyzedTransfer(
            record.getRowIndex(),
            "",
            record.getOrigem(),
            record.getDestino(),
            record.getMotivo(),
            type
        ));
        continue;
      }

      // Transferências de jogadores detectadas
      for (PlayerTransfer pt : detected) {
        for (String playerName : pt.getPlayerNames()) {
          playerCount++;

          // Registra os times envolvidos (normaliza para display consistente)
          teamsInvolved.add(pt.getFromTeam());
          teamsInvolved.add(pt.getToTeam());

          transfers.add(new AnalyzedTransfer(
              record.getRowIndex(),
              playerName,
              pt.getFromTeam(),
              pt.getToTeam(),
              record.getMotivo(),
              "PLAYER"
          ));
        }
      }
    }

    // Remove times com nome vazio (linhas mal formadas)
    teamsInvolved.remove("");
    teamsInvolved.remove(null);

    return new AnalysisReport(
        records.size(),
        playerCount,
        financialCount,
        uncertainCount,
        teamsInvolved,
        transfers
    );
  }
}
