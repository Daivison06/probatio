# Probatio

[English](README.md) | **Português**

> *probatio* (latim): prova, verificação. No direito romano, a prova apresentada em juízo.

Probatio é uma skill para agentes de código (Claude Code, Codex e similares) que faz verificação formal de comportamento de software com TLA+. Ela parte do código real, constrói um modelo TLA+, roda o model checker TLC para explorar todos os estados possíveis dentro dos limites do modelo e, quando encontra um contraexemplo, tenta reproduzi-lo no código.

Serve para propriedades como: um pagamento nunca é processado duas vezes, um tenant nunca acessa dados de outro, um lease expirado nunca apaga a posse de outro dono, um contador concorrente nunca perde uma atualização.

## Como funciona

1. **Escopo**: define as perguntas, os contratos esperados e o que fica de fora.
2. **Investigação**: segue o código pelos pontos de entrada, mudanças de estado e recuperação de erros, e monta uma matriz de cenários.
3. **Modelagem incremental**: começa com o menor modelo fiel ao código e adiciona um mecanismo ou falha por vez.
4. **Instrumentação**: captura traces da implementação real.
5. **Validação de traces**: confere se o modelo admite as execuções observadas.
6. **Model checking**: roda o TLC e classifica cada contraexemplo como erro da propriedade, erro do modelo ou possível defeito.
7. **Reprodução**: tenta reproduzir no código cada defeito candidato.
8. **Relatório**: registra resultados, limites do modelo e o que ficou sem cobertura.

O fluxo completo está em [SKILL.md](SKILL.md) e os detalhes de cada etapa em [references/](references/).

Um resultado do TLC vale para o modelo finito verificado, não é uma prova ilimitada de que a implementação está correta. O relatório deixa esses limites explícitos.

## Requisitos

- Python 3
- Java (a versão depende do release do TLA+ usado)
- `tla2tools.jar`, baixado dos [releases oficiais do TLA+](https://github.com/tlaplus/tlaplus/releases)

## Instalação

Copie ou crie um link da pasta para o diretório de skills do seu agente. No Claude Code:

```bash
ln -s "$(pwd)" ~/.claude/skills/probatio
```

## Uso

Peça ao agente em linguagem natural:

- "Use $probatio to model payment retries and check duplicate-processing scenarios with TLC."
- "Use $probatio to model tenant access transitions and check isolation."
- "Resume $probatio from `.analysis/payment-retries/progress.md`."

Os artefatos de cada análise ficam em `.analysis/<run-id>/` no projeto analisado. Para criar essa pasta manualmente:

```bash
python3 scripts/prepare.py --source /caminho/do/repo --out /caminho/da/analise --scope "comportamento a verificar"
```

## Scripts

| Script | O que faz |
| --- | --- |
| `scripts/prepare.py` | Cria um workspace de análise novo, com a revisão do código registrada |
| `scripts/tlc.py` | Roda o TLC num modelo e configuração, com timeout e log |
| `scripts/tracecheck.py` | Valida um trace da implementação contra o modelo |
| `scripts/regress.py` | Roda a regressão de um manifesto de configurações |

## Exemplos

- [examples/counter](examples/counter/EXAMPLE.md): contador concorrente com perda de atualização.
- [examples/lease](examples/lease/EXAMPLE.md): lease expirado cuja liberação atrasada apaga a posse do novo dono.

```bash
python3 examples/lease/run.py --out /caminho/novo --jar /caminho/tla2tools.jar --java java
```

## Testes

```bash
TLA_JAR=/caminho/tla2tools.jar python3 -m unittest discover -s tests
```

Use `JAVA=/caminho/java` se o Java não estiver no `PATH`. Sem `TLA_JAR`, rodam só os testes que não precisam de Java; os testes do TLC são pulados.

## Origem

Probatio foi escrita de forma independente, inspirada nas skills públicas do [Specula](https://github.com/specula-org/Specula). Ela não depende do Specula nem de servidores MCP. As diferenças deliberadas estão em [references/provenance.md](references/provenance.md).
