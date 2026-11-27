import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.DiscoveryFilter;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.DiscoverySelectors;

public class ExecutarJUnit {
    private record Resultado(String nome, TestExecutionResult resultado) {}

    public static void main(String[] args) {
        List<DiscoverySelector> seletores = new ArrayList<>();
        for (String classe : args) {
            seletores.add(DiscoverySelectors.selectClass(classe));
        }

        var configuracao = new ConfigurationParameters() {
            public Optional<String> get(String chave) { return Optional.empty(); }
            public Optional<Boolean> getBoolean(String chave) { return Optional.empty(); }
            public int size() { return 0; }
        };

        var descoberta = new EngineDiscoveryRequest() {
            public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> tipo) {
                return seletores.stream().filter(tipo::isInstance).map(tipo::cast).toList();
            }
            public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> tipo) {
                return List.of();
            }
            public ConfigurationParameters getConfigurationParameters() { return configuracao; }
        };

        var engine = new JupiterTestEngine();
        var raiz = engine.discover(descoberta, UniqueId.forEngine(engine.getId()));
        var resultados = new ArrayList<Resultado>();
        var falhasDeEstrutura = new ArrayList<Throwable>();
        var listener = new EngineExecutionListener() {
            @Override
            public void executionFinished(TestDescriptor teste, TestExecutionResult resultado) {
                if (teste.isTest()) {
                    resultados.add(new Resultado(nomeComCaso(teste), resultado));
                } else if (resultado.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                    resultado.getThrowable().ifPresent(falhasDeEstrutura::add);
                }
            }
        };
        engine.execute(new ExecutionRequest(raiz, listener, configuracao));

        System.out.printf("Engine: %s %s%n", engine.getId(), engine.getVersion().orElse("?"));
        System.out.printf("Java: %s%n%n", System.getProperty("java.version"));
        var totais = new EnumMap<TestExecutionResult.Status, Integer>(TestExecutionResult.Status.class);
        resultados.sort(java.util.Comparator.comparing(Resultado::nome));
        for (Resultado item : resultados) {
            var status = item.resultado().getStatus();
            totais.merge(status, 1, Integer::sum);
            System.out.printf("[%s] %s%n", status, item.nome());
            item.resultado().getThrowable().ifPresent(falha -> {
                falha.printStackTrace(System.out);
                System.out.println();
            });
        }
        System.out.printf("%nExecutados: %d | Aprovados: %d | Falhas: %d | Abortados: %d%n",
                resultados.size(),
                totais.getOrDefault(TestExecutionResult.Status.SUCCESSFUL, 0),
                totais.getOrDefault(TestExecutionResult.Status.FAILED, 0),
                totais.getOrDefault(TestExecutionResult.Status.ABORTED, 0));
        for (Throwable falha : falhasDeEstrutura) {
            System.out.println("Falha na estrutura do JUnit:");
            falha.printStackTrace(System.out);
        }
        if (resultados.isEmpty() || !falhasDeEstrutura.isEmpty()
                || totais.getOrDefault(TestExecutionResult.Status.SUCCESSFUL, 0) != resultados.size()) {
            System.exit(1);
        }
    }

    private static String nomeComCaso(TestDescriptor teste) {
        String nome = teste.getDisplayName();
        if (nome.startsWith("CT")) {
            return nome;
        }
        return teste.getParent()
                .map(TestDescriptor::getDisplayName)
                .filter(pai -> pai.startsWith("CT"))
                .map(pai -> pai + " / " + nome)
                .orElse(nome);
    }
}
