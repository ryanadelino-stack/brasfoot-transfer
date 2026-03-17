package br.brasfoot.transfer.service;

import br.brasfoot.transfer.model.PlayerTransfer;
import br.brasfoot.transfer.model.TransferRecord;
import br.brasfoot.transfer.util.StringNormalizer;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

/**
 * Classifica cada linha do arquivo de transferencias da liga EBL.
 *
 * Padroes detectados na planilha real (542 linhas analisadas):
 *
 *   SIMPLES        : "ARTHUR", "SAYMON", "ERICK PULGAR"
 *   POR            : "POR YGOR VINHAS", "POR CLEISON"
 *   TROCA (X POR Y): "LUCAS VILLALBA POR CLAYTON"
 *   TROCA EXPLICITA: "CAUÃ GODOY TROCA POR EDUARDO PEARSON"
 *   PELO/PELA      : "VITINHO PELO ALLAN SANTOS"
 *   VEM PARA       : "FERNANDO VEM PARA O SANTA CATARINA"
 *   MOTIVO:        : "MOTIVO: LUCAS ALVES"
 *   PARENTESES     : "(FELIPPE BORGES)", "(CASSIO GABRIEL)"
 *   MAIS           : "+ BELTRAME", "LUIS FELIPE + BELTRAME"
 *   VIRGULA        : "ADRIANO MICHAEL JACKSON, GETTERSON, RONAN"
 *   E (multiplos)  : "MAURICIO E MATHEUS HENRIQUE", "COMPRA DOS ZAGUEIROS GENILSON E KANU"
 *   POSICAO PREFIX : "ZAGUEIRO BRUNO LUIZ", "GOLEIRO LUCAS"
 *   POSICAO SUFFIX : "THAWAM GOLEIRO"
 *   COMPRA DO      : "COMPRA DO LATERAL ESQUERDO LIVERSON"
 *   VAI/VEM        : "VAI GOLEIRO LUCAS E VEM GOLEIRO HARRISON E VOLANTE JADSON"
 */
@Service
public class TransferClassifierService {

  // ─── TERMOS FINANCEIROS ──────────────────────────────────────────────────────
  // Todos ja normalizados (sem acentos, minusculos)

  private static final Set<String> FINANCIAL_EXACT = Set.of(
      "devolucao", "devolvendo", "teste", "bbb", "presente",
      "devolucao de negociacao", "por devolucao", "devolucao emprestimo",
      "emprestimo", "doacao", "salario", "juiz", "indicacao",
      "premiacoes", "premiacao", "pix errado", "mandou errado",
      "piloto", "funcao coordenacao", "funcao dep organiz",
      "diferenca", "restante", "sudeste", "nordeste",
      "volta do dinheiro", "pk"
  );

  private static final Set<String> FINANCIAL_PREFIXES = Set.of(
      "salari", "vip", "presente", "premiac",
      "doac", "soc", "investimento",
      "juiz", "indicac", "funcao",
      "upgrade", "emprestimo pagamento",
      "por sav", "por mod ", "por devoluc",
      "por modo player", "por score player",
      "por capitar", "por capitao modo",
      "por equipe modo",
      "devolvendo", "bbb",
      "por premiacao",
      "primeiro div", "vice div", "terceiro div", "quarto div", "campeao div",
      "primeiro cop", "vice cop", "terceiro cop", "quarto cop",
      "por copa", "por star", "star ",
      "estrela ",
      "compra de milhoes", "ajuda no modo"
  );

  private static final Set<String> FINANCIAL_CONTAINS = Set.of(
      "modo player", "score player", "capitao modo", "capitao mode",
      "div 1", "div 2", "div 3", "div 4", "div 5", "div 6",
      "div 7", "div 8", "div 9", "div 10", "div 11", "div 12",
      "divisao 1", "divisao 2", "divisao 3", "divisao 4", "divisao 5",
      "divisao 6", "divisao 7", "divisao 8", "divisao 9", "divisao 10",
      "divisao 11", "divisao 12",
      "copa ebl", "norte oeste", "sav regional", "sav de divisao",
      "sav estadual", "sav copa", "salario modo", "salario coord",
      "por mod norte", "por mod sul", "por mod leste", "por mod ",
      "emprestimo pagamento", "compra de milhoes",
      "premiacao div", "premiacoes div",
      "campeao div", "vice div", "terceiro div", "quarto div", "primeiro div",
      "campeao copa", "vice copa", "terceiro copa", "quarto copa",
      "semi norte", "semi sul", "semi leste", "semi oeste",
      "por semi ", "vip master", "vip premium", "vip anterior",
      "funcao dep", "funcao coord",
      "negociacao cancelada", "anjos ciente",
      "ajuda no modo", "matismo"
  );

  // ─── BLACKLIST: palavras que sozinhas nao sao nomes de jogadores ─────────────

  private static final Set<String> NAME_BLACKLIST = Set.of(
      "acesso", "teste", "devolucao", "devolvendo", "emprestimo",
      "presente", "doacao", "premiacao", "salario", "juiz", "indicacao",
      "funcao", "upgrade", "bbb", "vip", "investimento",
      "piloto", "pix", "errado", "mandou", "negociacao",
      "cancelada", "ciente", "anjos", "restante", "excluir",
      "acordo", "acerto", "diferenca", "sudeste", "nordeste",
      "volta", "dinheiro", "ajuda", "matismo", "saf", "real matismo",
      "pk", "emprestado", "0000000restante", "star"
  );

  // ─── PALAVRAS DE POSICAO (para remover do entorno do nome) ──────────────────

  private static final List<String> POSITION_WORDS = List.of(
      "goleiro", "zagueiro", "lateral direito", "lateral esquerdo", "lateral",
      "meia", "meia direito", "meia esquerdo", "meia atacante",
      "volante", "atacante", "atacande", // typo real na planilha
      "ponta direita", "ponta esquerda", "ponta",
      "centroavante", "armador", "ala"
  );

  // ─── PADROES REGEX ───────────────────────────────────────────────────────────

  // "VEM PARA O ...", "VEM PRO ...", "VEM PRA ..."
  private static final Pattern VEM_PARA_CLUBE = Pattern.compile(
      "\\s+vem\\s+(para\\s+o|para\\s+a|pro|pra|para)\\s+[\\w\\s\\-]+$",
      Pattern.CASE_INSENSITIVE);

  // "MOTIVO: X"
  private static final Pattern MOTIVO_PREFIX = Pattern.compile(
      "^motivo\\s*:\\s*", Pattern.CASE_INSENSITIVE);

  // "(NOME)" - nome entre parenteses
  private static final Pattern PARENTHESES = Pattern.compile(
      "\\(([^)]+)\\)");

  // "COMPRA DO/DOS/DA/DAS/DE POSICAO? NOME"
  private static final Pattern COMPRA_PREFIX = Pattern.compile(
      "^compra\\s+d[oaes]{1,2}\\s+", Pattern.CASE_INSENSITIVE);

  // "VAI POSICAO NOME E VEM POSICAO NOME ..."
  private static final Pattern VAI_VEM = Pattern.compile(
      "\\b(vai|vem)\\b", Pattern.CASE_INSENSITIVE);

  // ─── SEPARADORES DE MULTIPLOS JOGADORES ─────────────────────────────────────

  // "X TROCA POR Y", "X PELO Y", "X PELA Y", "X POR Y" (simples troca)
  private static final Pattern TROCA_PELO = Pattern.compile(
      "(.+?)\\s+(?:troca\\s+por|pelo|pela)\\s+(.+)",
      Pattern.CASE_INSENSITIVE);

  // "X POR Y" — diferente de "POR X" (prefixo simples)
  // Só e troca se X nao for vazio e nao for financeiro
  private static final Pattern POR_SEPARADOR = Pattern.compile(
      "^(.+?)\\s+por\\s+(.+)$",
      Pattern.CASE_INSENSITIVE);

  // ─── METODO PRINCIPAL ────────────────────────────────────────────────────────

  public List<PlayerTransfer> classify(TransferRecord record) {
    String motivo = record.getMotivo();
    String norm   = StringNormalizer.normalize(motivo);

    if (isFinancial(norm)) return List.of();

    List<String> names = extractPlayerNames(norm, motivo);
    if (names.isEmpty()) return List.of();

    List<PlayerTransfer> transfers = new ArrayList<>();
    for (String name : names) {
      transfers.add(new PlayerTransfer(
          record, List.of(name),
          record.getOrigem(), record.getDestino(),
          computeConfidence(norm, name)
      ));
    }
    return transfers;
  }

  // ─── DETECCAO FINANCEIRA ─────────────────────────────────────────────────────

  public boolean isFinancial(String norm) {
    if (norm.isBlank()) return true;
    if (FINANCIAL_EXACT.contains(norm)) return true;
    for (String fc : FINANCIAL_CONTAINS) {
      if (norm.contains(fc)) return true;
    }
    for (String fp : FINANCIAL_PREFIXES) {
      if (norm.startsWith(fp)) return true;
    }
    return false;
  }

  // ─── EXTRACAO DE NOMES ───────────────────────────────────────────────────────

  List<String> extractPlayerNames(String norm, String original) {
    List<String> names = new ArrayList<>();

    // --- Pre-processamento ---

    // 1. Remove "MOTIVO: "
    Matcher mMotivo = MOTIVO_PREFIX.matcher(norm);
    if (mMotivo.find()) norm = norm.substring(mMotivo.end()).strip();

    // 2. Extrai nomes entre parenteses se houver
    Matcher mParen = PARENTHESES.matcher(norm);
    List<String> fromParens = new ArrayList<>();
    StringBuffer sbParen = new StringBuffer();
    while (mParen.find()) {
      String inside = mParen.group(1).strip();
      if (!inside.isBlank()) fromParens.add(inside.toUpperCase());
      mParen.appendReplacement(sbParen, " ");
    }
    mParen.appendTail(sbParen);
    if (!fromParens.isEmpty()) {
      // Se havia so parenteses, usa eles; senao combina com o restante
      String remainder = sbParen.toString().strip();
      if (remainder.isBlank() || isFinancial(StringNormalizer.normalize(remainder))) {
        fromParens.forEach(n -> tryAddName(names, n));
        return names;
      }
      // Caso contrario, adiciona os de parenteses e continua processando o restante
      fromParens.forEach(n -> tryAddName(names, n));
      norm = StringNormalizer.normalize(remainder);
      if (norm.isBlank()) return names;
    }

    // 3. Remove "VEM PARA O CLUBE" / "VEM PRO CLUBE"
    norm = VEM_PARA_CLUBE.matcher(norm).replaceAll("").strip();

    // 4. Remove "_EXCLUIR" ao final
    if (norm.endsWith(" excluir")) {
      norm = norm.substring(0, norm.length() - 8).strip();
    }

    // 5. Remove "EMPRESTADO" ao final (emprestimo financeiro, nao transferencia)
    if (norm.endsWith(" emprestado") || norm.endsWith(" emprestada")) {
      return names; // e emprestimo, nao transferencia permanente
    }

    // 6. Remove "RESTANTE" / "0000000RESTANTE" (pagamento complementar)
    if (norm.startsWith("restante") || norm.startsWith("0") || norm.equals("de arrascaeta")) {
      // "DE ARRASCAETA" e um nome valido, trata especialmente
      if (norm.equals("de arrascaeta")) {
        tryAddName(names, "DE ARRASCAETA");
        return names;
      }
      if (norm.startsWith("0")) return names;
      if (norm.startsWith("restante")) return names;
    }

    // 7. Trata "COMPRA DO/DOS [POSICAO] NOME1, NOME2 E NOME3"
    Matcher mCompra = COMPRA_PREFIX.matcher(norm);
    if (mCompra.find()) {
      String rest = norm.substring(mCompra.end()).strip();
      rest = stripPositionPrefix(rest);
      // "E" pode separar multiplos: "GENILSON E KANU"
      splitAndAdd(names, rest);
      return names;
    }

    // 8. Trata "VAI POSICAO NOME E VEM POSICAO NOME"
    if (VAI_VEM.matcher(norm).find() && norm.contains(" e ")) {
      String[] parts = norm.split("\\s+e\\s+");
      for (String part : parts) {
        String p = part.replaceAll("^(vai|vem)\\s+", "").strip();
        p = stripPositionPrefix(p);
        p = stripPositionSuffix(p);
        tryAddName(names, p.toUpperCase());
      }
      if (!names.isEmpty()) return names;
    }

    // 9. Trata "POR X" (prefixo simples — origin envia X para destino)
    if (norm.startsWith("por ")) {
      String rest = norm.substring(4).strip();
      // Verifica se e "POR X TROCA POR Y" ou "POR X (TROCA POR Y)"
      Matcher mTrocaInternal = Pattern.compile(
          "^(.+?)\\s+(?:troca\\s+por|pelo|pela)\\s+(.+)$", Pattern.CASE_INSENSITIVE
      ).matcher(rest);
      if (mTrocaInternal.find()) {
        tryAddName(names, stripPositionWords(mTrocaInternal.group(1)).toUpperCase());
        tryAddName(names, stripPositionWords(mTrocaInternal.group(2)).toUpperCase());
        return names;
      }
      // Verifica se ha "(TROCA POR Y)" no final
      Matcher mTrocaParen = Pattern.compile(
          "^(.+?)\\s*\\(troca\\s+por\\s+(.+?)\\)\\s*$", Pattern.CASE_INSENSITIVE
      ).matcher(rest);
      if (mTrocaParen.find()) {
        tryAddName(names, stripPositionWords(mTrocaParen.group(1)).toUpperCase());
        tryAddName(names, stripPositionWords(mTrocaParen.group(2)).toUpperCase());
        return names;
      }
      if (!isFinancial(StringNormalizer.normalize(rest))) {
        tryAddName(names, stripPositionWords(rest).toUpperCase());
      }
      return names;
    }

    // 10. Trata "+ X" ou "X + Y"
    if (norm.startsWith("+ ")) {
      tryAddName(names, norm.substring(2).strip().toUpperCase());
      return names;
    }
    if (norm.contains(" + ")) {
      for (String part : norm.split("\\s*\\+\\s*")) {
        tryAddName(names, part.strip().toUpperCase());
      }
      return names;
    }

    // 11. Trata separadores de troca: "X TROCA POR Y", "X PELO Y", "X PELA Y"
    Matcher mTroca = TROCA_PELO.matcher(norm);
    if (mTroca.find()) {
      tryAddName(names, stripPositionWords(mTroca.group(1)).toUpperCase());
      tryAddName(names, stripPositionWords(mTroca.group(2)).toUpperCase());
      return names;
    }

    // 12. Trata "X POR Y" (troca simples — ambos sao jogadores)
    Matcher mPor = POR_SEPARADOR.matcher(norm);
    if (mPor.find()) {
      String left  = mPor.group(1).strip();
      String right = mPor.group(2).strip();
      // So e troca se ambos os lados parecem nomes (nao financeiros, com tamanho razoavel)
      if (!isFinancial(left) && !isFinancial(right)
          && left.length() >= 3 && right.length() >= 3
          && !NAME_BLACKLIST.contains(left) && !NAME_BLACKLIST.contains(right)) {
        tryAddName(names, stripPositionWords(left).toUpperCase());
        tryAddName(names, stripPositionWords(right).toUpperCase());
        return names;
      }
    }

    // 13. Trata multiplos por virgula: "ADRIANO, GETTERSON, RONAN"
    if (norm.contains(",")) {
      for (String part : norm.split(",")) {
        String p = part.strip();
        p = stripPositionWords(p);
        tryAddName(names, p.toUpperCase());
      }
      return names;
    }

    // 14. Trata multiplos por " E ": "MAURICIO E MATHEUS HENRIQUE"
    // Cuidado: "E" pode ser inicial de sobrenome (ex: "CARLOS E SILVA")
    // So separa por " E " se ambos os lados tiverem pelo menos 3 chars e nao forem financeiros
    if (norm.contains(" e ")) {
      String[] parts = norm.split("\\s+e\\s+", 2);
      String left  = parts[0].strip();
      String right = parts[1].strip();
      // Heuristica: separa por "E" so se o lado esquerdo tiver no maximo 2 palavras
      // (evita partir "CARLOS E SILVA" em dois nomes)
      long leftWords = Arrays.stream(left.split("\\s+")).count();
      if (leftWords <= 2 && left.length() >= 3 && right.length() >= 3
          && !isFinancial(left) && !isFinancial(right)) {
        tryAddName(names, stripPositionWords(left).toUpperCase());
        tryAddName(names, stripPositionWords(right).toUpperCase());
        return names;
      }
    }

    // 15. Nome simples: remove prefixo de posicao e sufixo de posicao
    String clean = stripPositionPrefix(norm);
    clean = stripPositionSuffix(clean);
    tryAddName(names, clean.toUpperCase());

    return names;
  }

  // ─── HELPERS ─────────────────────────────────────────────────────────────────

  /** Divide por virgula ou " E " e adiciona cada parte */
  private void splitAndAdd(List<String> names, String text) {
    if (text.contains(",")) {
      for (String p : text.split(",")) {
        String s = p.strip();
        if (!s.isBlank()) tryAddName(names, stripPositionWords(s).toUpperCase());
      }
    } else if (text.contains(" e ")) {
      for (String p : text.split("\\s+e\\s+")) {
        String s = p.strip();
        if (!s.isBlank()) tryAddName(names, stripPositionWords(s).toUpperCase());
      }
    } else {
      tryAddName(names, stripPositionWords(text).toUpperCase());
    }
  }

  /** Remove prefixo de posicao: "ZAGUEIRO BRUNO LUIZ" → "BRUNO LUIZ" */
  private String stripPositionPrefix(String text) {
    String lower = text.toLowerCase().strip();
    for (String pos : POSITION_WORDS) {
      if (lower.startsWith(pos + " ")) {
        return text.substring(pos.length()).strip();
      }
    }
    // "REAL NOME" — "REAL" e marcador, nao posicao
    if (lower.startsWith("real ") && lower.length() > 5) {
      return text.substring(5).strip();
    }
    return text;
  }

  /** Remove sufixo de posicao: "THAWAM GOLEIRO" → "THAWAM" */
  private String stripPositionSuffix(String text) {
    String lower = text.toLowerCase().strip();
    for (String pos : POSITION_WORDS) {
      if (lower.endsWith(" " + pos)) {
        return text.substring(0, text.length() - pos.length()).strip();
      }
    }
    return text;
  }

  /** Remove qualquer palavra de posicao do texto */
  private String stripPositionWords(String text) {
    String result = stripPositionPrefix(text.strip());
    result = stripPositionSuffix(result.strip());
    return result;
  }

  private void tryAddName(List<String> names, String candidate) {
    if (candidate == null) return;
    String c = candidate.strip();
    if (c.isBlank() || c.length() < 3) return;

    // Remove caracteres de pontuacao isolados
    c = c.replaceAll("^[.,;:!?+\\-]+|[.,;:!?+\\-]+$", "").strip();
    if (c.isBlank() || c.length() < 3) return;

    String norm = StringNormalizer.normalize(c);
    if (NAME_BLACKLIST.contains(norm)) return;
    if (isFinancial(norm)) return;

    // Evita duplicatas (mesmo nome normalizado)
    for (String existing : names) {
      if (StringNormalizer.normalize(existing).equals(norm)) return;
    }

    names.add(c.toUpperCase());
  }

  private double computeConfidence(String norm, String name) {
    if (StringNormalizer.normalize(name).equals(norm)) return 1.0;
    return 0.75;
  }
}
