package br.brasfoot.transfer.service;

import br.brasfoot.transfer.util.StringNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Lê e escreve arquivos .ban do Brasfoot.
 * Mantém um mapa em memória com os objetos desserializados (um por clube)
 * para que múltiplas transferências sejam acumuladas antes de reescrever.
 */
@Service
public class BanFileService {

  /** Mapa: nomeNormalizado → objeto e.t desserializado */
  private final Map<String, Object> loadedBans = new LinkedHashMap<>();

  /** Mapa: nomeNormalizado → nome original do arquivo (sem .ban) */
  private final Map<String, String> originalNames = new LinkedHashMap<>();

  /** Conjunto de times cujo .ban foi modificado */
  private final Set<String> dirtyTeams = new HashSet<>();

  /**
   * Mapeamento manual: nomeNormalizado-do-time → chave interna do .ban
   * Ex: "flamengo" → "flarj"
   * Tem prioridade sobre o matching automático.
   * Populado via loadMappings().
   */
  private final Map<String, String> manualMappings = new HashMap<>();

  // ─── Carregamento ────────────────────────────────────────────────────────────

  /**
   * Carrega todos os arquivos .ban fornecidos pelo usuário.
   * Deve ser chamado antes de qualquer operação de transferência.
   */
  public void loadAll(MultipartFile[] banFiles) throws IOException {
    loadedBans.clear();
    originalNames.clear();
    dirtyTeams.clear();
    manualMappings.clear();

    for (MultipartFile f : banFiles) {
      if (f == null || f.isEmpty()) continue;
      String fileName = f.getOriginalFilename();
      if (fileName == null) continue;

      // Remove extensão .ban
      String teamName = fileName.endsWith(".ban")
          ? fileName.substring(0, fileName.length() - 4)
          : fileName;

      String key = StringNormalizer.normalize(teamName);

      // Salva bytes em arquivo temporário para desserializar
      Path tmp = Files.createTempFile("brasfoot-ban-", ".ban");
      try {
        f.transferTo(tmp);
        Object teamObj = readSerialized(tmp);
        loadedBans.put(key, teamObj);
        originalNames.put(key, teamName);
      } catch (ClassNotFoundException e) {
        throw new IOException("Falha ao ler " + fileName + ": classe não encontrada no classpath. "
            + "Certifique-se de que as classes e.g e e.t estão presentes.", e);
      } finally {
        Files.deleteIfExists(tmp);
      }
    }
  }

  /**
   * Registra mapeamentos manuais: nome-do-time-no-excel → nome-do-arquivo-ban (sem .ban).
   * Chamado após loadAll(), tem prioridade sobre o matching automático.
   *
   * @param mappings Map de { "Flamengo" → "flarj", "PSV" → "psv_hol" }
   */
  public void loadMappings(Map<String, String> mappings) {
    if (mappings == null) return;
    for (Map.Entry<String, String> entry : mappings.entrySet()) {
      String teamKey = StringNormalizer.normalize(entry.getKey());
      String banKey  = StringNormalizer.normalize(
          entry.getValue().replaceAll("\.ban$", "")
      );
      if (!teamKey.isBlank() && !banKey.isBlank()) {
        manualMappings.put(teamKey, banKey);
      }
    }
  }

  /** Retorna true se o arquivo .ban do clube foi carregado. */
  public boolean hasBan(String teamName) {
    return resolveKey(teamName) != null;
  }

  /** Recupera o objeto do time (e.t) pelo nome. */
  public Object getBan(String teamName) {
    String key = resolveKey(teamName);
    return key != null ? loadedBans.get(key) : null;
  }

  /** Marca o time como modificado (dirty). */
  public void markDirty(String teamName) {
    String key = resolveKey(teamName);
    if (key != null) dirtyTeams.add(key);
    else dirtyTeams.add(StringNormalizer.normalize(teamName));
  }

  /**
   * Resolve o nome do time para a chave interna do mapa.
   * Estrategia em cascata:
   *  1. Igualdade exata normalizada             ("ajax" == "ajax")
   *  2. search contido em key                   ("ajax" in "ajax hol")
   *  3. key contido em search                   ("psv" in "psv eindhoven")
   *  4. Primeiro token igual                    ("sparta" == "sparta" de "sparta hol")
   *  5. Fuzzy Jaro-Winkler >= 0.88 em qualquer token do key vs search
   */
  private String resolveKey(String teamName) {
    if (teamName == null || teamName.isBlank()) return null;
    String search = StringNormalizer.normalize(teamName);

    // 0. Mapeamento manual tem prioridade absoluta
    if (manualMappings.containsKey(search)) {
      String mappedKey = manualMappings.get(search);
      if (loadedBans.containsKey(mappedKey)) return mappedKey;
    }

    // 1. Exato
    if (loadedBans.containsKey(search)) return search;

    String bestKey   = null;
    double bestScore = 0.0;

    for (String key : loadedBans.keySet()) {
      double score = 0.0;

      // 2. search contido em key  ("ajax" in "ajax hol")
      if (key.contains(search)) {
        score = 4.0;
      }
      // 3. key contido em search  ("psv" in "psv eindhoven")
      else if (search.contains(key)) {
        score = 3.0;
      }
      else {
        // 4. Primeiro token do key == primeiro token do search
        String keyFirst    = key.split("\\s+")[0];
        String searchFirst = search.split("\\s+")[0];
        if (keyFirst.equals(searchFirst) && keyFirst.length() >= 3) {
          score = 2.5;
        }
        // 5. Fuzzy: maior similaridade Jaro-Winkler entre tokens do key e do search
        else {
          double fuzzy = bestFuzzy(key, search);
          if (fuzzy >= 0.88) score = fuzzy;
        }
      }

      if (score > bestScore) { bestScore = score; bestKey = key; }
    }

    return (bestScore > 0) ? bestKey : null;
  }

  /** Maior similaridade Jaro-Winkler entre qualquer par de tokens de a e b */
  private double bestFuzzy(String a, String b) {
    String[] tokA = a.split("\\s+");
    String[] tokB = b.split("\\s+");
    double best = 0.0;
    for (String ta : tokA) {
      for (String tb : tokB) {
        if (ta.length() < 3 || tb.length() < 3) continue;
        double sim = jaroWinkler(ta, tb);
        if (sim > best) best = sim;
      }
    }
    // Tambem compara as strings inteiras
    double full = jaroWinkler(a, b);
    return Math.max(best, full);
  }

  /** Implementacao simples de Jaro-Winkler sem dependencia extra */
  private double jaroWinkler(String s1, String s2) {
    if (s1.equals(s2)) return 1.0;
    int len1 = s1.length(), len2 = s2.length();
    int matchDist = Math.max(Math.max(len1, len2) / 2 - 1, 0);
    boolean[] s1m = new boolean[len1], s2m = new boolean[len2];
    int matches = 0;
    for (int i = 0; i < len1; i++) {
      int lo = Math.max(0, i - matchDist), hi = Math.min(i + matchDist + 1, len2);
      for (int j = lo; j < hi; j++) {
        if (!s2m[j] && s1.charAt(i) == s2.charAt(j)) {
          s1m[i] = s2m[j] = true; matches++; break;
        }
      }
    }
    if (matches == 0) return 0.0;
    double t = 0;
    int k = 0;
    for (int i = 0; i < len1; i++) {
      if (!s1m[i]) continue;
      while (!s2m[k]) k++;
      if (s1.charAt(i) != s2.charAt(k)) t += 0.5;
      k++;
    }
    double jaro = (matches / (double) len1 + matches / (double) len2 + (matches - t) / matches) / 3.0;
    int prefix = 0;
    for (int i = 0; i < Math.min(4, Math.min(len1, len2)); i++) {
      if (s1.charAt(i) == s2.charAt(i)) prefix++; else break;
    }
    return jaro + prefix * 0.1 * (1 - jaro);
  }

  public Set<String> getModifiedTeamNames() {
    Set<String> result = new LinkedHashSet<>();
    for (String key : dirtyTeams) {
      result.add(originalNames.getOrDefault(key, key));
    }
    return result;
  }

  // ─── Serialização dos .ban modificados ──────────────────────────────────────

  /**
   * Serializa todos os .ban modificados (dirty) para um mapa de bytes.
   * Chave: nome do arquivo (ex: "Cascavel.ban")
   * Valor: bytes do arquivo serializado
   */
  public Map<String, byte[]> serializeModified() throws IOException {
    Map<String, byte[]> result = new LinkedHashMap<>();
    for (String key : dirtyTeams) {
      Object teamObj = loadedBans.get(key);
      if (teamObj == null) continue;

      String name = originalNames.getOrDefault(key, key) + ".ban";
      byte[] bytes = serializeToBytes(teamObj);
      result.put(name, bytes);
    }
    return result;
  }

  // ─── Acesso à lista de jogadores via reflexão ────────────────────────────────

  /**
   * Retorna a lista de jogadores (campo "l") do objeto e.t.
   * Usa reflexão para ser robusto a ofuscação.
   */
  @SuppressWarnings("unchecked")
  public List<Object> getPlayerList(Object teamObj) {
    Object val = getField(teamObj, "l");
    if (val instanceof List<?> list) return (List<Object>) list;

    // Fallback: procura o primeiro ArrayList que contém objetos e.g
    Class<?> cls = teamObj.getClass();
    for (Field f : getAllFields(cls)) {
      f.setAccessible(true);
      try {
        Object v = f.get(teamObj);
        if (v instanceof List<?> lst && !lst.isEmpty() && isPlayerObject(lst.get(0))) {
          return (List<Object>) lst;
        }
      } catch (Exception ignored) {}
    }
    return new ArrayList<>();
  }

  // ─── I/O e reflexão ─────────────────────────────────────────────────────────

  private Object readSerialized(Path path) throws IOException, ClassNotFoundException {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(path));
         ObjectInputStream ois = new ObjectInputStream(in)) {
      return ois.readObject();
    }
  }

  private byte[] serializeToBytes(Object obj) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(baos))) {
      oos.writeObject(obj);
      oos.flush();
    }
    return baos.toByteArray();
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

  private List<Field> getAllFields(Class<?> cls) {
    List<Field> fields = new ArrayList<>();
    while (cls != null && cls != Object.class) {
      fields.addAll(Arrays.asList(cls.getDeclaredFields()));
      cls = cls.getSuperclass();
    }
    return fields;
  }

  private boolean isPlayerObject(Object obj) {
    if (obj == null) return false;
    String cn = obj.getClass().getName();
    return cn.equals("e.g") || cn.endsWith(".g");
  }
}
