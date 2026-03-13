package e;

import java.io.Serializable;

/**
 * Jogador – classe original do Brasfoot (e.g).
 *
 * IMPORTANTE: o nome do pacote, da classe e os campos (nomes + tipos) DEVEM ser idênticos
 * ao que está no JAR do jogo para que a deserialização Java funcione corretamente.
 * O serialVersionUID foi extraído de um .ban real.
 */
public class g implements Serializable {
  private static final long serialVersionUID = 16L;

  public String  a;      // nome do jogador
  public int     aid;
  public boolean b;      // estrela
  public int     c;      // habilidade/atributo principal
  public int     d;      // idade
  public int     e;      // grupo de posição (0=GK, 1=DEF-LAT, 2=DEF-ZAG, 3=MID, 4=ATT)
  public int     f;      // titular (1=titular, 0=reserva)
  public int     g;      // posição detalhada (código interno)
  public int     h;      // lado/função (código interno)
  public int     hash;   // pé dominante / código interno
  public int     i;      // característica (código interno)
  public boolean j;      // top mundial
  public int     sid;
  public int     tid;
}
