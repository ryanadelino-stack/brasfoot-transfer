package br.brasfoot.transfer.model;

import java.util.List;
import java.util.Set;

/**
 * Relatório completo de uma sessão de transferências.
 * Retornado como JSON junto com o ZIP dos .ban modificados.
 */
public class TransferReport {

  private final int                  totalRows;
  private final int                  financialSkipped;
  private final int                  uncertainSkipped;
  private final int                  successCount;
  private final int                  notFoundCount;
  private final int                  banMissingCount;
  private final int                  errorCount;
  private final List<TransferResult> results;
  private final Set<String>          modifiedTeams;  // times com .ban alterado

  public TransferReport(int totalRows,
                        int financialSkipped,
                        int uncertainSkipped,
                        int successCount,
                        int notFoundCount,
                        int banMissingCount,
                        int errorCount,
                        List<TransferResult> results,
                        Set<String> modifiedTeams) {
    this.totalRows        = totalRows;
    this.financialSkipped = financialSkipped;
    this.uncertainSkipped = uncertainSkipped;
    this.successCount     = successCount;
    this.notFoundCount    = notFoundCount;
    this.banMissingCount  = banMissingCount;
    this.errorCount       = errorCount;
    this.results          = results;
    this.modifiedTeams    = modifiedTeams;
  }

  public int                  getTotalRows()        { return totalRows;        }
  public int                  getFinancialSkipped() { return financialSkipped; }
  public int                  getUncertainSkipped() { return uncertainSkipped; }
  public int                  getSuccessCount()     { return successCount;     }
  public int                  getNotFoundCount()    { return notFoundCount;    }
  public int                  getBanMissingCount()  { return banMissingCount;  }
  public int                  getErrorCount()       { return errorCount;       }
  public List<TransferResult> getResults()          { return results;          }
  public Set<String>          getModifiedTeams()    { return modifiedTeams;    }
}
