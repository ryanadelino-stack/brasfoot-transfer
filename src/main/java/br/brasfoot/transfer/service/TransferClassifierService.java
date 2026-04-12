package br.brasfoot.transfer.service;

import br.brasfoot.transfer.model.PlayerTransfer;
import br.brasfoot.transfer.model.TransferRecord;
import br.brasfoot.transfer.util.StringNormalizer;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

/**
 * Classifica cada linha do arquivo de transferências.
 *
 * Padrões de JOGADORES suportados (detectados na planilha real):
 *   SIMPLES          : "ARTHUR", "Neymar", "Federico Redondo"
 *   POR              : "POR YGOR VINHAS", "POR CLEISON"
 *   TROCA (X POR Y) : "LUCAS VILLALBA POR CLAYTON"
 *   TROCA EXPLICITA  : "CAUÃ GODOY TROCA POR EDUARDO PEARSON"
 *   PELO/PELA        : "VITINHO PELO ALLAN SANTOS"
 *   VEM PARA         : "SALDANHA VEM PARA O FERROVIÁRIO-CE"
 *   NOME DO CLUBE VEM: "Asier Hierro do União PR vem para o Porto Vitória"
 *   NOME - CLUBE VEM : "Bruno Tabata - União PR vem para o Porto Vitória"
 *   MOTIVO:          : "MOTIVO: LUCAS ALVES"
 *   PARENTESES       : "(FELIPPE BORGES)"
 *   MAIS             : "+ BELTRAME", "LUIS FELIPE + BELTRAME"
 *   VIRGULA          : "ADRIANO MICHAEL JACKSON, GETTERSON, RONAN"
 *   E (multiplos)    : "MAURICIO E MATHEUS HENRIQUE"
 *   POSICAO PREFIX   : "ZAGUEIRO BRUNO LUIZ", "vol : Cuéllar", "ZAG : LUCAS RIBEIRO"
 *   POSICAO SUFFIX   : "THAWAM GOLEIRO"
 *   COMPRA DO        : "COMPRA DO LATERAL ESQUERDO LIVERSON"
 *   COMPRA DE        : "Compra de Javier Camejo"
 *   JOGADOR NOME     : "Jogador Jefinho, atacante", "Jogador Rafik Belghali vem do..."
 *   VAI/VEM          : "VAI GOLEIRO LUCAS E VEM GOLEIRO HARRISON E VOLANTE JADSON"
 *   VALOR INTERCALADO: "Órri Óskarsson 30 M Marquinhos 20 M"
 *   COMPRA/PAGAMENTO : "Compra pelo jogador adailson" (extrai nome)
 *                      "Pagamento pelo jogador X" (FINANCEIRO — é pagamento, não transferência)
 */
@Service
public class TransferClassifierService {

  // ─── TERMOS FINANCEIROS ──────────────────────────────────────────────────────
  // Todos já normalizados (sem acentos, minúsculos).

  private static final Set<String> FINANCIAL_EXACT = Set.of(
      // Genéricos
      "devolucao", "devolvendo", "teste", "testando", "testando2", "bbb",
      "presente", "devolucao de negociacao", "por devolucao", "devolucao emprestimo",
      "emprestimo", "doacao", "salario", "juiz", "indicacao",
      "premiacoes", "premiacao", "pix errado", "mandou errado",
      "piloto", "funcao coordenacao", "funcao dep organiz",
      "diferenca", "restante", "sudeste", "nordeste", "volta do dinheiro", "pk",
      // Formato B
      "bonus", "tribunal", "mkt", "mod", "modo player", "estadual",
      "campeao", "artilheiro", "se vira", "video yt", "ebl bet pagou errado",
      // Padrões novos
      "sp a", "sp b",
      "melhor goleiro", "melhor atacante", "melhor meia",
      "melhor zagueiro", "melhor lateral", "melhor volante"
  );

  private static final Set<String> FINANCIAL_PREFIXES = Set.of(
      // Salário e variantes
      "salari",
      // VIP/Compra institucional
      "vip", "compra de milhoes",
      // Prêmio/premiação
      "premiac",
      // Doação
      "doac",
      // Sócio
      "soc",
      // Investimento
      "investimento",
      // Juiz
      "juiz",
      // Indicação
      "indicac",
      // Função
      "funcao",
      // Upgrade
      "upgrade",
      // Pagamento de empréstimo
      "emprestimo pagamento",
      // Contexto de mod
      "por sav", "por mod ", "por devoluc",
      "por modo player", "por score player", "por capitar", "por capitao modo", "por equipe modo",
      // Devolução
      "devolvendo",
      // BBB
      "bbb",
      // Premiação divisional
      "por premiacao",
      "primeiro div", "vice div", "terceiro div", "quarto div", "campeao div",
      "primeiro cop", "vice cop", "terceiro cop", "quarto cop",
      // Copa
      "por copa",
      // Estrela
      "por star", "star ", "estrela ",
      // Formato B
      "pagamento ", "pagamento-",
      "premiacoes individ",
      "salario sav", "salario savs", "50m salario",
      "mod bahia", "mod modo player", "mod copa", "mod div",
      "campeao bahia", "vice bahia",
      "restante mod", "por mod champions",
      // Padrões novos desta planilha
      "remover ",         // "remover saldanha" → remover do elenco, não é transferência
      "por estrela",      // "por estrela" → pagamento por estrela, não jogador
      "caridade ",        // "caridade empréstimos..."
      "verificar ",       // "verificar savs"
      "traga o ",         // "traga o leandro, miserável" → pedido informal, não transferência
      "chegou nas ",      // "chegou nas quartas do mineiro..." → premiação estadual
      "salario presidente", // "salario presidente estadual"
      "salario moderacao",  // "salario moderação"
      "devolvendo o ",      // "devolvendo o dinheiro que a mula..."
      "restante do pagamento", // "restante do pagamento do X"
      "devolucao do salario",  // "devolução do salario da moderação..."
      "ajuda no modo"
  );

  private static final Set<String> FINANCIAL_CONTAINS = Set.of(
      // Modo player
      "modo player", "score player", "capitao modo", "capitao mode",
      // Divisional
      "div 1", "div 2", "div 3", "div 4", "div 5", "div 6",
      "div 7", "div 8", "div 9", "div 10", "div 11", "div 12",
      "divisao 1", "divisao 2", "divisao 3", "divisao 4", "divisao 5",
      "divisao 6", "divisao 7", "divisao 8", "divisao 9", "divisao 10",
      "divisao 11", "divisao 12",
      // Copa/SAV
      "copa ebl", "norte oeste", "sav regional", "sav de divisao",
      "sav estadual", "sav copa",
      // Salário/coordenação
      "salario modo", "salario coord",
      // Mod geográfico
      "por mod norte", "por mod sul", "por mod leste", "por mod ",
      // Empréstimo pagamento
      "emprestimo pagamento",
      // Compra de milhões
      "compra de milhoes",
      // Premiação divisional
      "premiacao div", "premiacoes div",
      "campeao div", "vice div", "terceiro div", "quarto div", "primeiro div",
      "campeao copa", "vice copa", "terceiro copa", "quarto copa",
      // Semi-finais
      "semi norte", "semi sul", "semi leste", "semi oeste", "por semi ",
      // VIP variantes
      "vip master", "vip premium", "vip anterior",
      // Função
      "funcao dep", "funcao coord",
      // Cancelamento
      "negociacao cancelada", "anjos ciente",
      // Ajuda no modo
      "ajuda no modo", "matismo",
      // Formato B
      "pagou errado",
      "premiacoes individuais", "premiacoes individuas", "por melhor",
      // Padrões novos desta planilha
      "chegou nas quartas",        // premiação estadual
      "pagamento por jogador",     // pagamento de transferência, NÃO a transferência
      "pagamento pelo jogador",    // idem
      "pagamento da troca",        // idem
      "verificar savs",
      "caridade emprestimo",       // caridade/empréstimo informal
      "salario presidente",
      "devolvendo o dinheiro",
      "restante do pagamento",
      "devolucao transferencia",
      "devolucao de pagamento",
      "salario e bonus modo",      // "salário e bônus modo player"
      "salario bonus modo",
      "pagamento salario",
      "devolucao duplicada",
      "pagamento duplicidade",
      "resenha"                    // "salário resenha" → "resenha" = nome da liga/modo
  );

  // ─── BLACKLIST DE NOMES ───────────────────────────────────────────────────────

  private static final Set<String> NAME_BLACKLIST = Set.of(
      "acesso", "teste", "testando", "devolucao", "devolvendo", "emprestimo",
      "presente", "doacao", "premiacao", "salario", "juiz", "indicacao",
      "funcao", "upgrade", "bbb", "vip", "investimento",
      "piloto", "pix", "errado", "mandou", "negociacao",
      "cancelada", "ciente", "anjos", "restante", "excluir",
      "acordo", "acerto", "diferenca", "sudeste", "nordeste",
      "volta", "dinheiro", "ajuda", "matismo", "saf", "real matismo",
      "pk", "emprestado", "0000000restante", "star",
      "bonus", "tribunal", "mkt", "mod", "estadual", "campeao",
      "artilheiro", "testando2", "video", "yt", "jogador",
      "pagamento", "remover", "verificar", "caridade", "traga",
      "miseravel", "chegou"
  );

  // ─── PALAVRAS DE POSIÇÃO ─────────────────────────────────────────────────────

  private static final List<String> POSITION_WORDS = List.of(
      "goleiro", "zagueiro", "lateral direito", "lateral esquerdo", "lateral",
      "meia", "meia direito", "meia esquerdo", "meia ofensivo", "meia atacante",
      "volante", "atacante", "atacande", "centroavante", "armador", "ala",
      "ponta direita", "ponta esquerda", "ponta"
  );

  // Abreviações de posição (para o padrão "pos : NOME" ou "pos: NOME")
  private static final List<String> POSITION_ABBREVS = List.of(
      "vol", "zag", "lat", "mei", "ata", "gol", "cb", "rb", "lb",
      "cm", "dm", "am", "fw", "meia", "zagueiro", "lateral", "goleiro", "atacante"
  );

  // ─── PADRÕES REGEX ───────────────────────────────────────────────────────────

  // "VEM PARA O/PRO/PRA [CLUBE]" — remove o destino do texto
  private static final Pattern VEM_PARA_CLUBE = Pattern.compile(
      "\\s+vem\\s+(para\\s+o|para\\s+a|para|pro|pra)\\s+[\\w\\s\\-]+$",
      Pattern.CASE_INSENSITIVE);

  // "MOTIVO: X"
  private static final Pattern MOTIVO_PREFIX = Pattern.compile(
      "^motivo\\s*:\\s*", Pattern.CASE_INSENSITIVE);

  // "(NOME)" — extrai conteúdo de parênteses
  private static final Pattern PARENTHESES = Pattern.compile("\\(([^)]+)\\)");

  // "COMPRA DO/DOS/DE/PELO/PELA JOGADOR? NOME" (e typo "CONPRA")
  private static final Pattern COMPRA_PREFIX = Pattern.compile(
      "^(?:con?pra)\\s+(?:d[oaes]{1,2}|pelo|pela|de)\\s+(?:jogador[a-z:]*\\s+)?",
      Pattern.CASE_INSENSITIVE);

  // Texto longo após extração de "COMPRA DO jogador NOME": remove " do CLUBE" / " da junto a..."
  private static final Pattern COMPRA_SUFFIX_CLUBE = Pattern.compile(
      "\\s+(?:d[ao]\\s+(?:junto\\s+a\\s+equipe\\s+|)|d[ao]\\s+|do\\s+)\\S[\\w\\s\\-]*$",
      Pattern.CASE_INSENSITIVE);

  // "NOME do CLUBE vem" ou "NOME - CLUBE vem"
  private static final Pattern NOME_DO_CLUBE_VEM = Pattern.compile(
      "^(.+?)\\s+(?:do|da|de)\\s+[\\w\\s\\-]+\\s+vem",
      Pattern.CASE_INSENSITIVE);

  // "NOME - CLUBE vem para o CLUBE2" (separador hífen)
  private static final Pattern NOME_HIFEN_CLUBE_VEM = Pattern.compile(
      "^(.+?)\\s*-\\s*[\\w\\s]+\\s+vem",
      Pattern.CASE_INSENSITIVE);

  // "Jogador NOME, posição" ou "Jogador NOME vem..."
  private static final Pattern JOGADOR_PREFIX = Pattern.compile(
      "^jogador[a-s]*\\s+", Pattern.CASE_INSENSITIVE);

  // "vol : NOME", "ZAG : NOME" — posição + dois-pontos
  private static final Pattern POSICAO_COLON = Pattern.compile(
      "^([a-z]{2,8})\\s*:\\s*", Pattern.CASE_INSENSITIVE);

  // Valores intercalados: "30 M", "20M" entre nomes
  private static final Pattern VALOR_M = Pattern.compile(
      "\\s+\\d+\\s*[Mm]\\b");

  // "X TROCA POR Y", "X PELO Y", "X PELA Y"
  private static final Pattern TROCA_PELO = Pattern.compile(
      "(.+?)\\s+(?:troca\\s+por|pelo|pela)\\s+(.+)",
      Pattern.CASE_INSENSITIVE);

  // "X POR Y" separador de troca
  private static final Pattern POR_SEPARADOR = Pattern.compile(
      "^(.+?)\\s+por\\s+(.+)$", Pattern.CASE_INSENSITIVE);

  // ─── MÉTODO PRINCIPAL ─────────────────────────────────────────────────────────

  public List<PlayerTransfer> classify(TransferRecord record) {
    String motivo = record.getMotivo();
    String norm   = StringNormalizer.normalize(motivo);

    if (isFinancial(norm)) return List.of();

    List<String> names = extractPlayerNames(norm, motivo);
    if (names.isEmpty()) return List.of();

    List<PlayerTransfer> transfers = new ArrayList<>();
    for (String name : names) {
      transfers.add(new PlayerTransfer(record, List.of(name),
          record.getOrigem(), record.getDestino(), computeConfidence(norm, name)));
    }
    return transfers;
  }

  // ─── DETECÇÃO FINANCEIRA ──────────────────────────────────────────────────────

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

  // ─── EXTRAÇÃO DE NOMES ───────────────────────────────────────────────────────

  List<String> extractPlayerNames(String norm, String original) {
    List<String> names = new ArrayList<>();

    // 1. Remove "MOTIVO: "
    Matcher mMotivo = MOTIVO_PREFIX.matcher(norm);
    if (mMotivo.find()) norm = norm.substring(mMotivo.end()).strip();

    // 2. Extrai nomes entre parênteses se houver
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
      String remainder = sbParen.toString().strip();
      if (remainder.isBlank() || isFinancial(StringNormalizer.normalize(remainder))) {
        fromParens.forEach(n -> tryAddName(names, n));
        return names;
      }
      fromParens.forEach(n -> tryAddName(names, n));
      norm = StringNormalizer.normalize(remainder);
      if (norm.isBlank()) return names;
    }

    // 3. "Jogador NOME, posição" ou "Jogador NOME vem..."
    Matcher mJogador = JOGADOR_PREFIX.matcher(norm);
    if (mJogador.find()) {
      String rest = norm.substring(mJogador.end()).strip();
      // Remove "vem do CLUBE para o CLUBE2"
      rest = VEM_PARA_CLUBE.matcher(rest).replaceAll("").strip();
      // Remove ", posição" suffix
      if (rest.contains(",")) rest = rest.split(",")[0].strip();
      rest = stripPositionWords(rest);
      tryAddName(names, rest.toUpperCase());
      return names;
    }

    // 4. "VOL : NOME" ou "ZAG : NOME"
    Matcher mPosCln = POSICAO_COLON.matcher(norm);
    if (mPosCln.find()) {
      String abbrev = mPosCln.group(1).toLowerCase();
      boolean isPos = POSITION_ABBREVS.stream().anyMatch(p -> p.equals(abbrev));
      if (isPos) {
        String rest = norm.substring(mPosCln.end()).strip();
        rest = stripPositionWords(rest);
        tryAddName(names, rest.toUpperCase());
        return names;
      }
    }

    // 5. "NOME do CLUBE vem para o CLUBE2"
    Matcher mNomeDo = NOME_DO_CLUBE_VEM.matcher(norm);
    if (mNomeDo.find()) {
      String candidate = mNomeDo.group(1).strip();
      candidate = stripPositionWords(candidate);
      if (!candidate.isBlank() && !isFinancial(StringNormalizer.normalize(candidate))) {
        tryAddName(names, candidate.toUpperCase());
        return names;
      }
    }

    // 6. "NOME - CLUBE vem para o CLUBE2"
    if (norm.contains("-") && norm.contains("vem")) {
      Matcher mHifen = NOME_HIFEN_CLUBE_VEM.matcher(norm);
      if (mHifen.find()) {
        String candidate = mHifen.group(1).strip();
        candidate = stripPositionWords(candidate);
        if (!candidate.isBlank() && !isFinancial(StringNormalizer.normalize(candidate))) {
          tryAddName(names, candidate.toUpperCase());
          return names;
        }
      }
    }

    // 7. Remove "_EXCLUIR" ou "_EMPRESTADO"
    if (norm.endsWith(" excluir")) norm = norm.substring(0, norm.length() - 8).strip();
    if (norm.endsWith(" emprestado") || norm.endsWith(" emprestada")) return names;

    // 8. Remove "restante"/"0000..."
    if (norm.startsWith("restante") || norm.matches("^0+.*")) return names;

    // 9. "DE ARRASCAETA" — nome com preposição DE no início
    if (norm.equals("de arrascaeta") || norm.startsWith("de ")) {
      if (!isFinancial(norm)) {
        tryAddName(names, norm.toUpperCase());
        return names;
      }
    }

    // 10. "COMPRA DO/DE/PELO jogador? NOME [do CLUBE]"
    Matcher mCompra = COMPRA_PREFIX.matcher(norm);
    if (mCompra.find()) {
      String rest = norm.substring(mCompra.end()).strip();
      // Remove " do CLUBE" ou " da junto a equipe CLUBE" suffix
      rest = COMPRA_SUFFIX_CLUBE.matcher(rest).replaceAll("").strip();
      rest = stripPositionPrefix(rest);
      rest = stripPositionSuffix(rest);
      if (rest.contains(",")) {
        splitAndAdd(names, rest);
      } else if (rest.contains(" e ")) {
        splitAndAdd(names, rest);
      } else {
        tryAddName(names, rest.toUpperCase());
      }
      return names;
    }

    // 11. Remove "VEM PARA O CLUBE" do final
    norm = VEM_PARA_CLUBE.matcher(norm).replaceAll("").strip();

    // 12. "POR X" simples
    if (norm.startsWith("por ")) {
      String rest = norm.substring(4).strip();
      Matcher mTrocaInternal = Pattern.compile(
          "^(.+?)\\s+(?:troca\\s+por|pelo|pela)\\s+(.+)$", Pattern.CASE_INSENSITIVE
      ).matcher(rest);
      if (mTrocaInternal.find()) {
        tryAddName(names, stripPositionWords(mTrocaInternal.group(1)).toUpperCase());
        tryAddName(names, stripPositionWords(mTrocaInternal.group(2)).toUpperCase());
        return names;
      }
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

    // 13. "+ X" ou "X + Y"
    if (norm.startsWith("+ ")) { tryAddName(names, norm.substring(2).strip().toUpperCase()); return names; }
    if (norm.contains(" + ")) {
      for (String part : norm.split("\\s*\\+\\s*")) tryAddName(names, part.strip().toUpperCase());
      return names;
    }

    // 14. Valores intercalados: "NOME1 30M NOME2 20M" → strip valores, split por espaço em branco extra
    if (VALOR_M.matcher(norm).find()) {
      String clean = VALOR_M.matcher(norm).replaceAll(" ").strip();
      // Se sobrou mais de um "nome" (multi-word), tenta separar em 2
      String[] parts = clean.split("\\s{2,}");
      if (parts.length >= 2) {
        for (String p : parts) tryAddName(names, p.strip().toUpperCase());
        if (!names.isEmpty()) return names;
      }
      // Fallback: trata como nome único sem os valores
      tryAddName(names, clean.toUpperCase());
      return names;
    }

    // 15. "X TROCA POR Y" ou "X PELO/PELA Y"
    Matcher mTroca = TROCA_PELO.matcher(norm);
    if (mTroca.find()) {
      tryAddName(names, stripPositionWords(mTroca.group(1)).toUpperCase());
      tryAddName(names, stripPositionWords(mTroca.group(2)).toUpperCase());
      return names;
    }

    // 16. "X POR Y" — troca simples
    Matcher mPor = POR_SEPARADOR.matcher(norm);
    if (mPor.find()) {
      String left = mPor.group(1).strip(), right = mPor.group(2).strip();
      if (!isFinancial(left) && !isFinancial(right)
          && left.length() >= 3 && right.length() >= 3
          && !NAME_BLACKLIST.contains(left) && !NAME_BLACKLIST.contains(right)) {
        tryAddName(names, stripPositionWords(left).toUpperCase());
        tryAddName(names, stripPositionWords(right).toUpperCase());
        return names;
      }
    }

    // 17. Múltiplos por vírgula
    if (norm.contains(",")) {
      for (String part : norm.split(",")) {
        tryAddName(names, stripPositionWords(part.strip()).toUpperCase());
      }
      return names;
    }

    // 18. Múltiplos por " E " (heurística: lado esquerdo ≤ 2 palavras)
    if (norm.contains(" e ")) {
      String[] parts = norm.split("\\s+e\\s+", 2);
      String left = parts[0].strip(), right = parts[1].strip();
      long leftWords = Arrays.stream(left.split("\\s+")).count();
      if (leftWords <= 2 && left.length() >= 3 && right.length() >= 3
          && !isFinancial(left) && !isFinancial(right)) {
        tryAddName(names, stripPositionWords(left).toUpperCase());
        tryAddName(names, stripPositionWords(right).toUpperCase());
        return names;
      }
    }

    // 19. "VAI POSICAO NOME E VEM POSICAO NOME"
    if (norm.contains("vem") || norm.contains("vai")) {
      String[] parts = norm.split("\\s+e\\s+");
      if (parts.length > 1) {
        List<String> candidates = new ArrayList<>();
        for (String part : parts) {
          String p = part.replaceAll("(?i)^(vai|vem)\\s+", "").strip();
          p = stripPositionPrefix(p);
          p = stripPositionSuffix(p);
          if (p.length() >= 3 && !isFinancial(StringNormalizer.normalize(p)))
            candidates.add(p.toUpperCase());
        }
        if (!candidates.isEmpty()) {
          candidates.forEach(n -> tryAddName(names, n));
          return names;
        }
      }
    }

    // 20. Nome simples (default)
    String clean = stripPositionPrefix(norm);
    clean = stripPositionSuffix(clean);
    tryAddName(names, clean.toUpperCase());

    return names;
  }

  // ─── HELPERS ─────────────────────────────────────────────────────────────────

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

  private String stripPositionPrefix(String text) {
    String lower = text.toLowerCase().strip();
    for (String pos : POSITION_WORDS) {
      if (lower.startsWith(pos + " ")) return text.substring(pos.length()).strip();
    }
    if (lower.startsWith("real ") && lower.length() > 5) return text.substring(5).strip();
    return text;
  }

  private String stripPositionSuffix(String text) {
    String lower = text.toLowerCase().strip();
    for (String pos : POSITION_WORDS) {
      if (lower.endsWith(" " + pos)) return text.substring(0, text.length() - pos.length()).strip();
    }
    return text;
  }

  private String stripPositionWords(String text) {
    return stripPositionSuffix(stripPositionPrefix(text.strip()).strip()).strip();
  }

  private void tryAddName(List<String> names, String candidate) {
    if (candidate == null) return;
    String c = candidate.strip().replaceAll("^[.,;:!?+\\-]+|[.,;:!?+\\-]+$", "").strip();
    if (c.isBlank() || c.length() < 3) return;
    String norm = StringNormalizer.normalize(c);
    if (NAME_BLACKLIST.contains(norm)) return;
    if (isFinancial(norm)) return;
    // Evita duplicatas
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
