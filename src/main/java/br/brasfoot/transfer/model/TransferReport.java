package br.brasfoot.transfer.model;

import java.util.List;
import java.util.Set;

public class TransferReport {

  private final int                  totalRows;
  private final int                  financialSkipped;
  private final int                  uncertainSkipped;
  private final int                  successCount;
  private final int                  notFoundCount;
  private final int                  banMissingCount;
  private final int                  errorCount;
  private final int                  rosterFullCount;       // bloqueados: elenco cheio
  private final int                  alreadyTransferred;    // bloqueados: dupla venda ou recém-chegado
  private final int                  missingTeamCount;      // ignorados: origem ou destino em branco
  private final int                  dismissedCount;        // dispensados: jogador removido do elenco
  private final List<TransferResult> results;
  private final Set<String>          modifiedTeams;

  public TransferReport(int totalRows, int financialSkipped, int uncertainSkipped,
                        int successCount, int notFoundCount, int banMissingCount,
                        int errorCount, int rosterFullCount, int alreadyTransferred,
                        int missingTeamCount, int dismissedCount,
                        List<TransferResult> results, Set<String> modifiedTeams) {
    this.totalRows          = totalRows;
    this.financialSkipped   = financialSkipped;
    this.uncertainSkipped   = uncertainSkipped;
    this.successCount       = successCount;
    this.notFoundCount      = notFoundCount;
    this.banMissingCount    = banMissingCount;
    this.errorCount         = errorCount;
    this.rosterFullCount    = rosterFullCount;
    this.alreadyTransferred = alreadyTransferred;
    this.missingTeamCount   = missingTeamCount;
    this.dismissedCount     = dismissedCount;
    this.results            = results;
    this.modifiedTeams      = modifiedTeams;
  }

  public int                  getTotalRows()          { return totalRows;          }
  public int                  getFinancialSkipped()   { return financialSkipped;   }
  public int                  getUncertainSkipped()   { return uncertainSkipped;   }
  public int                  getSuccessCount()       { return successCount;       }
  public int                  getNotFoundCount()      { return notFoundCount;      }
  public int                  getBanMissingCount()    { return banMissingCount;    }
  public int                  getErrorCount()         { return errorCount;         }
  public int                  getRosterFullCount()    { return rosterFullCount;    }
  public int                  getAlreadyTransferred() { return alreadyTransferred; }
  public int                  getMissingTeamCount()   { return missingTeamCount;   }
  public int                  getDismissedCount()     { return dismissedCount;     }
  public List<TransferResult> getResults()            { return results;            }
  public Set<String>          getModifiedTeams()      { return modifiedTeams;      }
}
