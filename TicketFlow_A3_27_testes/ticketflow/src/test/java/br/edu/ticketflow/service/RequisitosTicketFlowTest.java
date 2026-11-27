package br.edu.ticketflow.service;

import static br.edu.ticketflow.domain.StatusPedido.AGUARDANDO_PAGAMENTO;
import static br.edu.ticketflow.domain.StatusPedido.EXPIRADO;
import static br.edu.ticketflow.domain.StatusPedido.PAGO;
import static br.edu.ticketflow.domain.TipoIngresso.INTEIRA;
import static br.edu.ticketflow.domain.TipoIngresso.MEIA_ENTRADA;

import br.edu.ticketflow.domain.Cliente;
import br.edu.ticketflow.domain.Evento;
import br.edu.ticketflow.domain.ItemPedido;
import br.edu.ticketflow.domain.Pedido;
import br.edu.ticketflow.exception.RegraNegocioException;
import br.edu.ticketflow.repository.InMemoryEventoRepository;
import br.edu.ticketflow.repository.InMemoryPedidoRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static br.edu.ticketflow.domain.StatusPedido.CANCELADO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RequisitosTicketFlowTest {
    private static final Clock RELOGIO = Clock.fixed(
            Instant.parse("2026-11-18T13:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private static final LocalDate HOJE = LocalDate.now(RELOGIO);
    private static final LocalDateTime AGORA = LocalDateTime.now(RELOGIO);
    private static final String EVENTO = "EVT-001";
    private static final String CLIENTE_A = "CLI-001";
    private static final String CLIENTE_B = "CLI-002";
    private static final BigDecimal PRECO = new BigDecimal("90.00");

    private final InMemoryEventoRepository eventos = new InMemoryEventoRepository();
    private final InMemoryPedidoRepository pedidos = new InMemoryPedidoRepository();
    private final CalculadoraPreco precos = new CalculadoraPreco();
    private final ValidadorCliente clientes = new ValidadorCliente();
    private final VendaService vendas = new VendaService(eventos, pedidos, precos, RELOGIO);

    @Test
    @DisplayName("CT002 | Rejeita cliente a um dia de completar 16 anos")
    void rejeitaClienteAbaixoDaIdadeMinima() {
        Cliente cliente = new Cliente(CLIENTE_A, "Pessoa de Teste", "cliente@example.test",
                "12345678901", HOJE.minusYears(16).plusDays(1));

        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> clientes.validar(cliente, HOJE));
        assertEquals("Cliente menor de 16 anos", erro.getMessage());
    }

    @Test
    @DisplayName("CT003 | Reserva seis assentos e impede reserva conflitante")
    void reservaSeisAssentos() {
        List<String> selecionados = List.of("A1", "A2", "A3", "B1", "B2", "B3");
        salvarEvento(AGORA.plusDays(10), new BigDecimal("120.00"), selecionados);

        Pedido primeiro = vendas.criarPedido(CLIENTE_A, EVENTO, selecionados, INTEIRA, null);

        assertEquals(selecionados, primeiro.itens().stream().map(ItemPedido::assento).toList());
        assertEquals(AGUARDANDO_PAGAMENTO, primeiro.status());
        assertAll(
                () -> assertThrows(RegraNegocioException.class,
                        () -> vendas.criarPedido(CLIENTE_B, EVENTO, List.of("A1"), INTEIRA, null)),
                () -> assertEquals(1, pedidos.listar().size()));
    }

    @Test
    @DisplayName("CT004 | Rejeita sete ingressos sem gravar o pedido")
    void rejeitaSeteIngressos() {
        String evento = "EVT-002";
        List<String> assentos = List.of("C1", "C2", "C3", "C4", "C5", "C6", "C7");
        eventos.salvar(new Evento(evento, "Festival Java", AGORA.plusDays(10), PRECO, assentos));

        assertThrows(RegraNegocioException.class,
                () -> vendas.criarPedido(CLIENTE_A, evento, assentos, INTEIRA, null));
        assertTrue(pedidos.listar().isEmpty());
        Pedido novo = vendas.criarPedido(CLIENTE_A, evento, List.of("C1"), INTEIRA, null);
        assertEquals("C1", novo.itens().get(0).assento());
    }

    @Test
    @DisplayName("CT006 | Expira aos 15 minutos exatos e libera assento")
    void expiraAoCompletarQuinzeMinutos() {
        Pedido pedido = reservarA1();
        Clock quinzeMinutosDepois = Clock.offset(RELOGIO, Duration.ofMinutes(15));
        VendaService vendasDepois = new VendaService(eventos, pedidos, precos, quinzeMinutosDepois);

        int expirados = vendasDepois.expirarPedidos();

        assertAll(
                () -> assertEquals(1, expirados),
                () -> assertEquals(EXPIRADO, pedidos.buscarPorId(pedido.id()).orElseThrow().status()),
                () -> {
                    Pedido novaReserva = vendasDepois.criarPedido(CLIENTE_B, EVENTO, List.of("A1"), INTEIRA, null);
                    assertEquals("A1", novaReserva.itens().get(0).assento());
                });
    }

    @Test
    @DisplayName("CT007 | Arredonda meia-entrada de R$ 101,99 para R$ 51,00")
    void arredondaMeiaEntrada() {
        BigDecimal valor = precos.calcular(new BigDecimal("101.99"), MEIA_ENTRADA, null);

        assertEquals(new BigDecimal("51.00"), valor);
    }

    @Test
    @DisplayName("CT008 | Aplica FESTA10 uma vez ao subtotal de R$ 50,00")
    void aplicaCupomUmaVezPorPedido() {
        salvarEvento(AGORA.plusDays(10), new BigDecimal("25.00"), List.of("A1", "A2"));

        Pedido pedido = vendas.criarPedido(CLIENTE_A, EVENTO, List.of("A1", "A2"), INTEIRA, "FESTA10");

        assertEquals(new BigDecimal("40.00"), pedido.total());
    }

    @Test
    @DisplayName("CT010 | Confirma pagamento de R$ 90,00 para pedido de R$ 90,00")
    void aceitaPagamentoExato() {
        Pedido pedido = reservarA1();
        assertEquals(PRECO, pedido.total());

        vendas.pagar(pedido.id(), new BigDecimal("90.00"));

        Pedido confirmado = pedidos.buscarPorId(pedido.id()).orElseThrow();
        assertEquals(PAGO, confirmado.status());
        assertEquals(PRECO, confirmado.total());
    }

    @Test
    @DisplayName("CT011 | Rejeita pagamento de R$ 90,01 para pedido de R$ 90,00")
    void rejeitaPagamentoAcimaDoTotal() {
        Pedido pedido = reservarA1();
        assertEquals(PRECO, pedido.total());

        assertAll(
                () -> assertThrows(RegraNegocioException.class,
                        () -> vendas.pagar(pedido.id(), new BigDecimal("90.01"))),
                () -> assertEquals(AGUARDANDO_PAGAMENTO, pedidos.buscarPorId(pedido.id()).orElseThrow().status()));
    }

    @Test
    @DisplayName("CT016 | Rejeita cancelamento com antecedência de 47h59")
    void rejeitaCancelamentoComAntecedenciaInsuficiente() {
        salvarEvento(AGORA.plusHours(47).plusMinutes(59), PRECO, List.of("A1"));
        Pedido pedido = vendas.criarPedido(CLIENTE_A, EVENTO, List.of("A1"), INTEIRA, null);
        vendas.pagar(pedido.id(), PRECO);

        assertAll(
                () -> assertThrows(RegraNegocioException.class, () -> vendas.cancelar(pedido.id())),
                () -> assertEquals(PAGO, pedidos.buscarPorId(pedido.id()).orElseThrow().status()),
                () -> assertThrows(RegraNegocioException.class,
                        () -> vendas.criarPedido(CLIENTE_B, EVENTO, List.of("A1"), INTEIRA, null)));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("CT025 | Rejeita dados cadastrais malformados")
    @CsvSource(delimiter = '|', textBlock = """
            Nome em branco | ' ' | cliente@example.test | 12345678901 | Nome obrigatório
            E-mail sem arroba | Pessoa de Teste | cliente.example.test | 12345678901 | E-mail inválido
            CPF com dez dígitos | Pessoa de Teste | cliente@example.test | 1234567890 | CPF inválido
            """)
    void rejeitaDadosCadastraisInvalidos(String campo, String nome, String email, String cpf,
            String mensagemEsperada) {
        Cliente cliente = new Cliente(CLIENTE_A, nome, email, cpf, HOJE.minusYears(20));

        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> clientes.validar(cliente, HOJE), campo);
        assertEquals(mensagemEsperada, erro.getMessage());
    }


    @Test
    @DisplayName("CT001 | Aceita cliente no dia em que completa 16 anos")
    void aceitaIdadeMinima() {
        Cliente c = new Cliente(CLIENTE_A, "Pessoa de Teste", "cliente@example.test",
                "12345678901", HOJE.minusYears(16));
        assertDoesNotThrow(() -> clientes.validar(c, HOJE));
    }

    @Test
    @DisplayName("CT005 | Mantém reserva ativa aos 14min59s")
    void mantemReservaAntesDoLimite() {
        Pedido p = reservarA1();
        VendaService depois = new VendaService(eventos, pedidos, precos,
                Clock.offset(RELOGIO, Duration.ofSeconds(899)));
        assertEquals(0, depois.expirarPedidos());
        assertEquals(AGUARDANDO_PAGAMENTO, pedidos.buscarPorId(p.id()).orElseThrow().status());
    }

    @Test
    @DisplayName("CT009 | Recusa FESTA10 abaixo do subtotal mínimo")
    void recusaCupomAbaixoDoMinimo() {
        BigDecimal subtotal = new BigDecimal("49.99");
        salvarEvento(AGORA.plusDays(10), subtotal, List.of("A1"));
        try {
            Pedido p = vendas.criarPedido(CLIENTE_A, EVENTO, List.of("A1"), INTEIRA, "FESTA10");
            assertEquals(subtotal, p.total(), "O desconto não deve ser concedido");
        } catch (RegraNegocioException e) {
            assertTrue(pedidos.listar().isEmpty());
            Pedido semCupom = vendas.criarPedido(CLIENTE_A, EVENTO, List.of("A1"), INTEIRA, null);
            assertEquals(subtotal, semCupom.total());
        }
    }

    @Test
    @DisplayName("CT012 | Repetição idempotente do pagamento")
    void pagamentoIdempotente() {
        bloqueado("Gateway, chave de idempotência e registro de pagamento ausentes");
    }

    @Test
    @DisplayName("CT013 | Disputa simultânea pelo mesmo assento")
    void disputaSimultanea() throws Exception {
        salvarEvento(AGORA.plusDays(10), PRECO, List.of("A1"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier inicio = new CyclicBarrier(2);
        try {
            Future<Boolean> a = pool.submit(() -> tentarReserva(CLIENTE_A, inicio));
            Future<Boolean> b = pool.submit(() -> tentarReserva(CLIENTE_B, inicio));
            int sucessos = (a.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (b.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertAll(() -> assertEquals(1, sucessos),
                    () -> assertEquals(1, pedidos.listar().size()));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private boolean tentarReserva(String cliente, CyclicBarrier inicio) throws Exception {
        inicio.await(5, TimeUnit.SECONDS);
        try {
            vendas.criarPedido(cliente, EVENTO, List.of("A1"), INTEIRA, null);
            return true;
        } catch (RegraNegocioException e) {
            assertEquals("Assento indisponível", e.getMessage());
            return false;
        }
    }

    @Test
    @DisplayName("CT014 | Rejeição do segundo check-in")
    void segundoCheckin() {
        bloqueado("Entidade ingresso e operação de check-in ausentes");
    }

    @Test
    @DisplayName("CT015 | Aceita cancelamento exatamente 48 horas antes")
    void cancelaComQuarentaEOitoHoras() {
        salvarEvento(AGORA.plusHours(48), PRECO, List.of("A1"));
        Pedido p = vendas.criarPedido(CLIENTE_A, EVENTO, List.of("A1"), INTEIRA, null);
        vendas.pagar(p.id(), PRECO);
        vendas.cancelar(p.id());
        assertEquals(CANCELADO, pedidos.buscarPorId(p.id()).orElseThrow().status());
        assertEquals("A1", vendas.criarPedido(CLIENTE_B, EVENTO, List.of("A1"), INTEIRA, null).itens().get(0).assento());
    }

    @Test
    @DisplayName("CT017 | Consulta de pedido de outro cliente")
    void consultaAutorizada() {
        bloqueado("Serviço público de consulta com autorização ausente");
    }

    @ParameterizedTest(name = "Início relativo a T0: {0} segundo(s)")
    @ValueSource(longs = {-1, 0})
    @DisplayName("CT018 | Rejeita sessão passada ou já iniciada")
    void rejeitaSessaoPassada(long segundos) {
        salvarEvento(AGORA.plusSeconds(segundos), PRECO, List.of("A1"));
        assertAll(() -> assertThrows(RegraNegocioException.class,
                () -> vendas.criarPedido(CLIENTE_A, EVENTO, List.of("A1"), INTEIRA, null)),
                () -> assertTrue(pedidos.listar().isEmpty()));
    }

    @Test
    @DisplayName("CT019 | Piso zero para desconto superior ao subtotal")
    void pisoZero() {
        bloqueado("Não existe benefício elegível de R$ 10,00 para subtotal de R$ 8,00 na base");
    }

    @Test
    @DisplayName("CT020 | Libera assento após cancelamento confirmado")
    void liberaAssentoCancelado() {
        Pedido p = reservarA1();
        vendas.pagar(p.id(), PRECO);
        assertThrows(RegraNegocioException.class,
                () -> vendas.criarPedido(CLIENTE_B, EVENTO, List.of("A1"), INTEIRA, null));
        vendas.cancelar(p.id());
        Pedido b = vendas.criarPedido(CLIENTE_B, EVENTO, List.of("A1"), INTEIRA, null);
        assertEquals("A1", b.itens().get(0).assento());
        assertEquals(CANCELADO, pedidos.buscarPorId(p.id()).orElseThrow().status());
    }

    @Test
    @DisplayName("CT021 | Impede reserva pendente conflitante")
    void impedeReservaPendente() {
        Pedido p = reservarA1();
        VendaService depois = new VendaService(eventos, pedidos, precos,
                Clock.offset(RELOGIO, Duration.ofMinutes(1)));
        assertAll(() -> assertThrows(RegraNegocioException.class,
                () -> depois.criarPedido(CLIENTE_B, EVENTO, List.of("A1"), INTEIRA, null)),
                () -> assertEquals(1, pedidos.listar().size()),
                () -> assertEquals(AGUARDANDO_PAGAMENTO, p.status()));
    }

    @ParameterizedTest(name = "Quantidade: {0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("CT022 | Compra com um ingresso e rejeição de lista vazia")
    void limitesQuantidade(int quantidade) {
        salvarEvento(AGORA.plusDays(10), PRECO, List.of("A1"));
        if (quantidade == 0) {
            assertThrows(RegraNegocioException.class,
                    () -> vendas.criarPedido(CLIENTE_A, EVENTO, List.of(), INTEIRA, null));
            assertTrue(pedidos.listar().isEmpty());
        } else {
            Pedido p = vendas.criarPedido(CLIENTE_A, EVENTO, List.of("A1"), INTEIRA, null);
            assertEquals(1, p.itens().size());
            assertEquals(1, pedidos.listar().size());
        }
    }

    @Test
    @DisplayName("CT023 | Rejeita pagamento inferior ao total")
    void rejeitaPagamentoInferior() {
        Pedido p = reservarA1();
        assertAll(() -> assertThrows(RegraNegocioException.class,
                () -> vendas.pagar(p.id(), new BigDecimal("89.99"))),
                () -> assertEquals(AGUARDANDO_PAGAMENTO, pedidos.buscarPorId(p.id()).orElseThrow().status()));
    }

    @Test
    @DisplayName("CT024 | Rejeita pagamento de pedido expirado")
    void rejeitaPagamentoExpirado() {
        Pedido p = reservarA1();
        VendaService depois = new VendaService(eventos, pedidos, precos,
                Clock.offset(RELOGIO, Duration.ofSeconds(901)));
        assertEquals(1, depois.expirarPedidos());
        assertEquals(EXPIRADO, p.status());
        assertAll(() -> assertThrows(RegraNegocioException.class, () -> depois.pagar(p.id(), PRECO)),
                () -> assertEquals(EXPIRADO, pedidos.buscarPorId(p.id()).orElseThrow().status()));
    }

    @Test
    @DisplayName("CT026 | Emissão de um ingresso por item após pagamento")
    void emissaoIngressos() {
        bloqueado("Emissão de ingressos e confirmação de notificação ausentes");
    }

    @Test
    @DisplayName("CT027 | Limite de capacidade na entrada geral")
    void capacidadeEntradaGeral() {
        bloqueado("Modelo de entrada geral por capacidade ausente");
    }

    // Um caso bloqueado não é aprovação nem reprovação funcional.
    private void bloqueado(String motivo) {
        Assumptions.assumeTrue(false, "BLOQUEADO: " + motivo);
    }

    private Pedido reservarA1() {
        salvarEvento(AGORA.plusDays(10), PRECO, List.of("A1"));
        return vendas.criarPedido(CLIENTE_A, EVENTO, List.of("A1"), INTEIRA, null);
    }

    private void salvarEvento(LocalDateTime inicio, BigDecimal preco, List<String> assentos) {
        eventos.salvar(new Evento(EVENTO, "Festival Java", inicio, preco, assentos));
    }
}
