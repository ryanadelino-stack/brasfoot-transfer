package br.brasfoot.transfer.model;

/**
 * Representa uma linha bruta lida do arquivo Excel/CSV de transferências da liga.
 */
public class TransferRecord {

  private final int    rowIndex;
  private final String origem;
  private final String destino;
  private final String divisao;
  private final String valor;
  private final String motivo;
  private final String data;

  public TransferRecord(int rowIndex, String origem, String destino,
                        String divisao, String valor, String motivo, String data) {
    this.rowIndex = rowIndex;
    this.origem   = trim(origem);
    this.destino  = trim(destino);
    this.divisao  = trim(divisao);
    this.valor    = trim(valor);
    this.motivo   = trim(motivo);
    this.data     = trim(data);
  }

  private static String trim(String s) {
    return s == null ? "" : s.strip();
  }

  public int    getRowIndex() { return rowIndex; }
  public String getOrigem()   { return origem;   }
  public String getDestino()  { return destino;  }
  public String getDivisao()  { return divisao;  }
  public String getValor()    { return valor;    }
  public String getMotivo()   { return motivo;   }
  public String getData()     { return data;     }

  @Override
  public String toString() {
    return "TransferRecord{row=" + rowIndex + ", origem='" + origem + "', destino='" + destino
        + "', motivo='" + motivo + "'}";
  }
}
