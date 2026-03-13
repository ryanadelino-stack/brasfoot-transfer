package br.brasfoot.transfer.model;

import java.util.List;

/**
 * Uma transferência de jogador detectada a partir de uma linha do CSV.
 * Uma única linha pode gerar múltiplas transferências (ex: trocas "X POR Y").
 */
public class PlayerTransfer {

  private final TransferRecord sourceRecord;
  private final List<String>   playerNames;   // nomes extraídos do campo Motivo
  private final String         fromTeam;
  private final String         toTeam;
  private final double         confidence;    // 0.0 – 1.0

  public PlayerTransfer(TransferRecord sourceRecord,
                        List<String> playerNames,
                        String fromTeam,
                        String toTeam,
                        double confidence) {
    this.sourceRecord = sourceRecord;
    this.playerNames  = playerNames;
    this.fromTeam     = fromTeam;
    this.toTeam       = toTeam;
    this.confidence   = confidence;
  }

  public TransferRecord getSourceRecord() { return sourceRecord; }
  public List<String>   getPlayerNames()  { return playerNames;  }
  public String         getFromTeam()     { return fromTeam;     }
  public String         getToTeam()       { return toTeam;       }
  public double         getConfidence()   { return confidence;   }
}
