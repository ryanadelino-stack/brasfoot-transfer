package br.brasfoot.transfer.model;

/**
 * Resultado do processamento de uma transferência individual.
 */
public class TransferResult {

  public enum Status {
    SUCCESS,          // jogador encontrado e movido com sucesso
    NOT_FOUND,        // jogador não encontrado no .ban de origem
    BAN_NOT_PROVIDED, // arquivo .ban do clube origem ou destino não foi enviado
    SKIPPED_FINANCIAL,// linha identificada como transação financeira, não jogador
    SKIPPED_UNCERTAIN,// linha ambígua – não foi possível classificar com segurança
    ERROR             // erro inesperado durante a operação
  }

  private final int    rowIndex;
  private final String playerName;
  private final String fromTeam;
  private final String toTeam;
  private final String rawMotivo;
  private final Status status;
  private final String message;
  private final String matchedName; // nome real no .ban (pode diferir do CSV)
  private final double matchScore;  // similaridade da busca (0.0–1.0)

  private TransferResult(Builder b) {
    this.rowIndex    = b.rowIndex;
    this.playerName  = b.playerName;
    this.fromTeam    = b.fromTeam;
    this.toTeam      = b.toTeam;
    this.rawMotivo   = b.rawMotivo;
    this.status      = b.status;
    this.message     = b.message;
    this.matchedName = b.matchedName;
    this.matchScore  = b.matchScore;
  }

  // Getters
  public int    getRowIndex()    { return rowIndex;    }
  public String getPlayerName()  { return playerName;  }
  public String getFromTeam()    { return fromTeam;    }
  public String getToTeam()      { return toTeam;      }
  public String getRawMotivo()   { return rawMotivo;   }
  public Status getStatus()      { return status;      }
  public String getMessage()     { return message;     }
  public String getMatchedName() { return matchedName; }
  public double getMatchScore()  { return matchScore;  }

  // ─── Builder ────────────────────────────────────────────────────────────────

  public static Builder builder() { return new Builder(); }

  public static class Builder {
    private int    rowIndex;
    private String playerName  = "";
    private String fromTeam    = "";
    private String toTeam      = "";
    private String rawMotivo   = "";
    private Status status      = Status.ERROR;
    private String message     = "";
    private String matchedName = "";
    private double matchScore  = 0.0;

    public Builder rowIndex(int v)    { this.rowIndex = v;    return this; }
    public Builder playerName(String v) { this.playerName = v; return this; }
    public Builder fromTeam(String v)   { this.fromTeam = v;   return this; }
    public Builder toTeam(String v)     { this.toTeam = v;     return this; }
    public Builder rawMotivo(String v)  { this.rawMotivo = v;  return this; }
    public Builder status(Status v)     { this.status = v;     return this; }
    public Builder message(String v)    { this.message = v;    return this; }
    public Builder matchedName(String v){ this.matchedName = v; return this; }
    public Builder matchScore(double v) { this.matchScore = v; return this; }
    public TransferResult build()       { return new TransferResult(this); }
  }
}
