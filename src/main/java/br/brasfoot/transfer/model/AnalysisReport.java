package br.brasfoot.transfer.model;

import java.util.List;
import java.util.Set;

/**
 * Resultado da análise prévia do arquivo de transferências.
 * Retornado pelo endpoint /api/transfer/analyze (sem precisar dos .ban).
 */
public class AnalysisReport {

  private final int          totalRows;
  private final int          playerTransfersDetected;
  private final int          financialSkipped;
  private final int          uncertainSkipped;
  private final Set<String>  teamsInvolved;      // todos os times (origem + destino)
  private final List<AnalyzedTransfer> transfers; // prévia de cada transferência detectada

  public AnalysisReport(int totalRows,
                        int playerTransfersDetected,
                        int financialSkipped,
                        int uncertainSkipped,
                        Set<String> teamsInvolved,
                        List<AnalyzedTransfer> transfers) {
    this.totalRows               = totalRows;
    this.playerTransfersDetected = playerTransfersDetected;
    this.financialSkipped        = financialSkipped;
    this.uncertainSkipped        = uncertainSkipped;
    this.teamsInvolved           = teamsInvolved;
    this.transfers               = transfers;
  }

  public int                    getTotalRows()               { return totalRows;               }
  public int                    getPlayerTransfersDetected() { return playerTransfersDetected; }
  public int                    getFinancialSkipped()        { return financialSkipped;        }
  public int                    getUncertainSkipped()        { return uncertainSkipped;        }
  public Set<String>            getTeamsInvolved()           { return teamsInvolved;           }
  public List<AnalyzedTransfer> getTransfers()               { return transfers;               }

  // ─── Item individual da análise prévia ──────────────────────────────────────

  public record AnalyzedTransfer(
      int    rowIndex,
      String playerName,
      String fromTeam,
      String toTeam,
      String rawMotivo,
      String type        // "PLAYER" | "FINANCIAL" | "UNCERTAIN"
  ) {}
}
