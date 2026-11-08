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

- Um JDK 17 ou mais recente (um JRE não basta, porque as ferramentas rodam direto do código-fonte)
- `tla2tools.jar`, baixado dos [releases oficiais do TLA+](https://github.com/tlaplus/tlaplus/releases)

Os dois podem ser instalados sem permissão de administrador em `~/.probatio`, em versões fixas e com checksum verificado:

```bash
sh scripts/install.sh          # macOS e Linux
powershell scripts/install.ps1 # Windows
```

O instalador reaproveita um JDK que já esteja no `PATH` e só baixa o Temurin 21 quando não encontra nenhum.

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

Os artefatos de cada análise ficam em `.analysis/<run-id>/` no projeto analisado, a partir dos templates em `assets/workspace/`.

## Ferramentas

Todas as ferramentas estão num único arquivo-fonte e rodam direto, sem etapa de build:

| Comando | O que faz |
| --- | --- |
| `java scripts/Probatio.java tlc Model.tla Model.cfg --out DIR` | Roda o TLC num snapshot do modelo, com timeout, log e recibo com hash |
| `java scripts/Probatio.java trace Model.tla run.ndjson --out DIR` | Compila um trace da implementação numa checagem de alcançabilidade do TLC |
| `java scripts/Probatio.java regress suite.json --out DIR` | Roda um manifesto de regressão com casos de modelo e de trace |

`--jar` usa `~/.probatio/tla2tools.jar` por padrão, e o TLC roda no mesmo `java` que iniciou a ferramenta.

## Exemplos

- [examples/counter](examples/counter/EXAMPLE.md): contador concorrente com perda de atualização.
- [examples/lease](examples/lease/EXAMPLE.md): lease expirado cuja liberação atrasada apaga a posse do novo dono.

```bash
java examples/lease/LeaseExample.java --out /caminho/novo
```

## Testes

```bash
javac -d build/test scripts/Probatio.java examples/lease/LeaseExample.java tests/ProbatioTest.java
TLA_JAR=~/.probatio/tla2tools.jar java -cp build/test ProbatioTest
```

Sem `TLA_JAR`, rodam só os testes de validação de entrada; os testes do TLC são pulados.

## Origem

Probatio foi escrita de forma independente, inspirada nas skills públicas do [Specula](https://github.com/specula-org/Specula). Ela não depende do Specula nem de servidores MCP. As diferenças deliberadas estão em [references/provenance.md](references/provenance.md).
