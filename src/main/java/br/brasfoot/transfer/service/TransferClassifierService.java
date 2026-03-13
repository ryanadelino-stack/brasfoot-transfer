package br.brasfoot.transfer.service;

import br.brasfoot.transfer.model.PlayerTransfer;
import br.brasfoot.transfer.model.TransferRecord;
import br.brasfoot.transfer.util.StringNormalizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifica cada linha do arquivo de transferencias:
 * - Ignora transacoes financeiras (salarios, premiacoes, VIP, etc.)
 * - Detecta e extrai nomes de jogadores de transferencias reais
 *
 * IMPORTANTE: todos os valores nos sets ja estao normalizados (sem acentos, minusculos),
 * pois sempre comparamos contra StringNormalizer.normalize() antes de checar.
 */
@Service
public class TransferClassifierService {

  // --- Termos financeiros -------------------------------------------------------

  /** Prefixos: se o Motivo COMECA com qualquer um destes (normalizado), e financeiro */
  private static final Set<String> FINANCIAL_PREFIXES = Set.of(
      "salari", "vip", "presente", "premiac",
      "doac", "soc", "investimento",
      "juiz", "indicac", "funcao",
      "upgrade", "emprestimo pagamento",
      "por sav", "por mod", "por devoluc",
      "por modo player", "por score player", "por capitar", "por capitao",
      "devolvendo", "salario coord", "salario modo", "bbb",
      "por premiacao",
      "primeiro div", "vice div", "terceiro div", "quarto div", "campeao div",
      "primeiro cop", "vice cop", "terceiro cop", "quarto cop",
      "por copa", "por star", "star",
      "estrela "
  );

  /** Termos que, se o Motivo E exatamente um destes (normalizado), e financeiro */
  private static final Set<String> FINANCIAL_EXACT = Set.of(
      "devolucao", "devolvendo", "teste", "bbb", "presente",
      "devolucao de negociacao", "por devolucao", "devolucao emprestimo",
      "emprestimo", "doacao", "salario", "juiz", "indicacao",
      "premiacoes", "premiacao", "pix errado", "mandou errado",
      "piloto", "funcao coordenacao", "funcao dep organiz"
  );

  /** Substrings que, se CONTIDAS no Motivo (normalizado), indicam contexto financeiro */
  private static final Set<String> FINANCIAL_CONTAINS = Set.of(
      "modo player", "score player", "capitao modo", "capitao mode",
      "div 1", "div 2", "div 3", "div 4", "div 5", "div 6",
      "div 7", "div 8", "div 9", "div 10", "div 11", "div 12",
      "divisao 1", "divisao 2", "divisao 3", "divisao 4", "divisao 5",
      "divisao 6", "divisao 7", "divisao 8", "divisao 9", "divisao 10",
      "divisao 11", "divisao 12",
      "copa ebl", "norte oeste", "sav regional", "sav de divisao",
      "sav estadual", "sav copa", "salario modo", "salario coord",
      "por mod norte", "por mod sul", "por mod leste",
      "emprestimo pagamento", "compra de milhoes",
      "premiacao div", "premiacoes div",
      "campeao div", "vice div", "terceiro div", "quarto div", "primeiro div",
      "campeao copa", "vice copa", "terceiro copa", "quarto copa"
  );

  // --- Palavras que sozinhas NAO sao nomes de jogadores -------------------------

  private static final Set<String> NAME_BLACKLIST = Set.of(
      "acesso", "teste", "devolucao", "devolvendo", "emprestimo",
      "presente", "doacao", "premiacao", "salario",
      "juiz", "indicacao", "funcao", "upgrade", "bbb", "vip",
      "investimento", "pk", "piloto", "pix", "errado", "mandou",
      "negociacao", "cancelada", "ciente", "anjos", "restante",
      "excluir", "acordo", "acerto"
  );

  // --- Padroes Regex ------------------------------------------------------------

  // "VEM PARA O [CLUBE]" ou "VEM PRO [CLUBE]" - remove essa parte
  private static final Pattern VEM_PARA = Pattern.compile(
      "\\s+vem\\s+(para\\s+o|pro|pra)\\s+.+$", Pattern.CASE_INSENSITIVE);

  // "MOTIVO: " prefix
  private static final Pattern MOTIVO_PREFIX = Pattern.compile(
      "^motivo\\s*:\\s*", Pattern.CASE_INSENSITIVE);

  // "DEVOLUCAO DE NEGOCIACAO POR " prefix
  private static final Pattern DEVOLUCAO_POR = Pattern.compile(
      "^devoluc[ao]{1,2}\\s+de\\s+negociac[ao]{1,2}\\s+por\\s+", Pattern.CASE_INSENSITIVE);

  // -------------------------------------------------------------------------------

  /**
   * Tenta classificar um registro e extrair transferencias de jogadores.
   * Retorna lista vazia se for financeiro ou incerto.
   */
  public List<PlayerTransfer> classify(TransferRecord record) {
    String motivo = record.getMotivo();
    String norm   = StringNormalizer.normalize(motivo);

    if (isFinancial(norm)) {
      return List.of();
    }

    List<String> names = extractPlayerNames(norm, motivo);

    if (names.isEmpty()) {
      return List.of();
    }

    List<PlayerTransfer> transfers = new ArrayList<>();
    for (String name : names) {
      transfers.add(new PlayerTransfer(
          record,
          List.of(name),
          record.getOrigem(),
          record.getDestino(),
          computeConfidence(norm, name)
      ));
    }
    return transfers;
  }

  // --- Deteccao financeira ------------------------------------------------------

  public boolean isFinancial(String norm) {
    if (FINANCIAL_EXACT.contains(norm)) return true;

    for (String fc : FINANCIAL_CONTAINS) {
      if (norm.contains(fc)) return true;
    }

    for (String fp : FINANCIAL_PREFIXES) {
      if (norm.startsWith(fp)) return true;
    }

    return false;
  }

  // --- Extracao de nomes --------------------------------------------------------

  List<String> extractPlayerNames(String norm, String original) {
    List<String> names = new ArrayList<>();

    String work = norm;

    Matcher m1 = MOTIVO_PREFIX.matcher(work);
    if (m1.find()) work = work.substring(m1.end()).strip();

    Matcher m2 = DEVOLUCAO_POR.matcher(work);
    if (m2.find()) work = work.substring(m2.end()).strip();

    work = VEM_PARA.matcher(work).replaceAll("").strip();

    // Caso: "X POR Y" (troca de jogadores)
    if (work.contains(" por ") && !isFinancialContext(work)) {
      String[] parts = work.split("\\s+por\\s+", 2);
      tryAddName(names, parts[0].strip());
      tryAddName(names, parts[1].strip());
      return names;
    }

    // Caso: multiplos jogadores separados por virgula
    if (work.contains(",")) {
      for (String part : work.split(",")) {
        tryAddName(names, part.strip());
      }
      return names;
    }

    tryAddName(names, work);
    return names;
  }

  private boolean isFinancialContext(String norm) {
    for (String fp : FINANCIAL_PREFIXES) {
      if (norm.startsWith(fp)) return true;
    }
    for (String fc : FINANCIAL_CONTAINS) {
      if (norm.contains(fc)) return true;
    }
    return false;
  }

  private void tryAddName(List<String> names, String candidate) {
    String c = candidate.strip();
    if (c.isBlank() || c.length() < 2) return;

    String norm = StringNormalizer.normalize(c);
    if (NAME_BLACKLIST.contains(norm)) return;
    if (isFinancial(norm)) return;

    names.add(c.toUpperCase());
  }

  private double computeConfidence(String norm, String name) {
    if (StringNormalizer.normalize(name).equals(norm)) return 1.0;
    return 0.75;
  }
}
