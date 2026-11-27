# TicketFlow A3 — suíte de 27 casos
O código de produção original foi preservado. Os casos CT001–CT027 estão em
`ticketflow/src/test/java/br/edu/ticketflow/service/RequisitosTicketFlowTest.java`.

## Executar sem Maven (Windows)
1. Extraia o ZIP inteiro.
2. Tenha JDK 17 ou superior disponível no PATH.
3. Execute `executar-testes.cmd`. O relatório completo será salvo em `resultado-junit.txt`.

## Executar com Maven
Abra a pasta `ticketflow` e execute:
```bash
mvn -Dtest=RequisitosTicketFlowTest test
```
O teste de exemplo original (ExemploSmokeTest) não pertence aos 27 casos.

## Resultado real da rodada de 26/11/2026
- 27 casos especificados: 11 aprovados, 10 reprovados, 6 bloqueados.
- JUnit: 31 invocações (15 aprovadas, 10 falhas, 6 abortadas), pois CT018, CT022 e CT025 têm variações.
- Aprovados no recorte disponível: CT001, CT002, CT004, CT005, CT007, CT010, CT015, CT020, CT022, CT023, CT025.
- Reprovados: CT003, CT006, CT008, CT009, CT011, CT013, CT016, CT018, CT021, CT024.
- Bloqueados (abortados explicitamente): CT012, CT014, CT017, CT019, CT026, CT027.
- CT001/CT002/CT025 verificam o validador, não o cadastro persistente. CT015 e CT020 verificam estado e inventário, não emissão, uso de ingressos ou estorno.
- CT018 falha no instante exato de início, mas passa para sessão passada.
- CT013 é concorrente; o resultado pode variar conforme escalonamento.
- Os bloqueados são marcadores explícitos, não testes funcionais completos: não há operação correspondente ou massa elegível na base.
- A evidência da execução está em `evidencias/junit-execucao.txt`.
