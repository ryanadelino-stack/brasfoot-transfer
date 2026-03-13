package br.brasfoot.transfer.util;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Utilitário de normalização de strings para comparação de nomes de jogadores e clubes.
 */
public final class StringNormalizer {

  private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
  private static final Pattern NON_ALPHA  = Pattern.compile("[^a-z0-9 ]");
  private static final Pattern SPACES     = Pattern.compile("\\s+");

  private StringNormalizer() {}

  /**
   * Normaliza para comparação: remove acentos, coloca em minúsculas, remove
   * caracteres especiais e colapsa espaços múltiplos.
   *
   * Exemplo: "Grêmio " → "gremio"
   */
  public static String normalize(String input) {
    if (input == null) return "";
    String s = input.strip();
    // Remove Unicode "non-breaking space" e similares
    s = s.replace('\u00A0', ' ').replace('\u200B', ' ');
    // Decomposição + remoção de diacríticos
    s = Normalizer.normalize(s, Normalizer.Form.NFD);
    s = DIACRITICS.matcher(s).replaceAll("");
    s = s.toLowerCase();
    s = NON_ALPHA.matcher(s).replaceAll(" ");
    s = SPACES.matcher(s).replaceAll(" ").strip();
    return s;
  }

  /**
   * Versão em UPPERCASE normalizada – usada na classificação de motivos.
   */
  public static String normalizeUpper(String input) {
    return normalize(input).toUpperCase();
  }

  /**
   * Verifica se dois strings são iguais após normalização.
   */
  public static boolean equalsNormalized(String a, String b) {
    return normalize(a).equals(normalize(b));
  }

  /**
   * Verifica se {@code haystack} contém {@code needle} após normalização.
   */
  public static boolean containsNormalized(String haystack, String needle) {
    return normalize(haystack).contains(normalize(needle));
  }
}
