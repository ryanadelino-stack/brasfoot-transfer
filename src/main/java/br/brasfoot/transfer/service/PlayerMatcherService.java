package br.brasfoot.transfer.service;

import br.brasfoot.transfer.util.StringNormalizer;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Localiza um jogador pelo nome dentro da lista de jogadores de um .ban.
 *
 * Estratégia de matching (em ordem de preferência):
 *  1. Igualdade exata (após normalização de acentos e case)
 *  2. Substring bidirecional (o nome do CSV está contido no nome do .ban ou vice-versa)
 *  3. Similaridade Jaro-Winkler ≥ 0.88
 */
@Service
public class PlayerMatcherService {

  /** Score mínimo para aceitar um match fuzzy */
  private static final double FUZZY_THRESHOLD = 0.88;

  private final JaroWinklerSimilarity jaro = new JaroWinklerSimilarity();

  public record MatchResult(Object playerObj, String matchedName, double score) {}

  /**
   * Procura o jogador cujo nome mais se assemelha a {@code searchName} dentro de {@code players}.
   *
   * @return Optional com o melhor match, ou empty se nenhum satisfaz o threshold
   */
  public Optional<MatchResult> findBestMatch(List<Object> players, String searchName) {
    String searchNorm = StringNormalizer.normalize(searchName);

    MatchResult best = null;
    double bestScore = 0.0;

    for (Object player : players) {
      String name = getPlayerName(player);
      if (name == null || name.isBlank()) continue;

      String nameNorm = StringNormalizer.normalize(name);
      double score = computeScore(searchNorm, nameNorm);

      if (score > bestScore) {
        bestScore = score;
        best = new MatchResult(player, name, score);
      }
    }

    if (best != null && bestScore >= FUZZY_THRESHOLD) {
      return Optional.of(best);
    }
    return Optional.empty();
  }

  // ─── Score ──────────────────────────────────────────────────────────────────

  private double computeScore(String search, String candidate) {
    // 1. Exact match
    if (search.equals(candidate)) return 1.0;

    // 2. Contains (bidirectional)
    if (candidate.contains(search) || search.contains(candidate)) {
      // Penaliza um pouco se for substring muito curta
      int minLen = Math.min(search.length(), candidate.length());
      int maxLen = Math.max(search.length(), candidate.length());
      return 0.92 * ((double) minLen / maxLen) + 0.08;
    }

    // 3. Jaro-Winkler
    return jaro.apply(search, candidate);
  }

  // ─── Reflexão – leitura do nome ─────────────────────────────────────────────

  /**
   * Lê o nome do jogador do objeto (campo "a" ou "nome").
   */
  public String getPlayerName(Object player) {
    if (player == null) return null;
    Object val = getField(player, "a");
    if (val instanceof String s && !s.isBlank()) return s;
    val = getField(player, "nome");
    if (val instanceof String s && !s.isBlank()) return s;
    return null;
  }

  private Object getField(Object target, String name) {
    Class<?> cls = target.getClass();
    while (cls != null && cls != Object.class) {
      try {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
      } catch (NoSuchFieldException e) {
        cls = cls.getSuperclass();
      } catch (Exception e) {
        return null;
      }
    }
    return null;
  }

  /**
   * Lista todos os nomes de jogadores (útil para debug/relatório).
   */
  public List<String> listNames(List<Object> players) {
    List<String> names = new ArrayList<>();
    for (Object p : players) {
      String n = getPlayerName(p);
      if (n != null) names.add(n);
    }
    return names;
  }
}
