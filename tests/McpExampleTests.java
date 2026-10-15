import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import juno.Json;
import juno.Main;

/** A real interactive MCP client: replies must arrive before stdin is closed. */
public final class McpExampleTests {
    static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    static void check(boolean ok, Object detail) { if (!ok) throw new AssertionError(detail); }
    static Map<String,Object> object(Object value) { return Json.object(value); }
    static BigDecimal number(int value) { return BigDecimal.valueOf(value); }

    static final class Client implements AutoCloseable {
        final Process process;
        final BufferedWriter input;
        final BufferedReader output;
        final ExecutorService reader = Executors.newSingleThreadExecutor();
        final Path errors;
        int nextId = 100;
        Client(Path cwd, List<String> command, String name) throws Exception {
            errors = cwd.resolve(name + ".err");
            process = new ProcessBuilder(command).directory(cwd.toFile()).redirectError(errors.toFile()).start();
            input = process.outputWriter(StandardCharsets.UTF_8);
            output = process.inputReader(StandardCharsets.UTF_8);
        }
        void send(String text) throws IOException { input.write(text); input.newLine(); input.flush(); }
        Map<String,Object> receive() throws Exception {
            String text = reader.submit(output::readLine).get(10, TimeUnit.SECONDS);
            check(text != null, "Server closed stdout: " + Files.readString(errors));
            Map<String,Object> response = object(Json.parse(text));
            check(response.get("jsonrpc").equals("2.0") && response.containsKey("id"), response);
            check(response.containsKey("result") != response.containsKey("error"), response);
            return response;
        }
        Map<String,Object> request(String method, Map<String,Object> params) throws Exception {
            BigDecimal id = number(nextId++);
            send(Json.stringify(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params)));
            Map<String,Object> response = receive();
            check(id.equals(response.get("id")), response);
            return response;
        }
        void rawError(String text, int code) throws Exception {
            send(text);
            Map<String,Object> response = receive();
            check(response.get("id") == null, response);
            error(response, code);
        }
        @Override public void close() throws Exception {
            try {
                input.close();
                boolean exited = process.waitFor(10, TimeUnit.SECONDS);
                if (!exited) process.destroyForcibly().waitFor();
                check(exited && process.exitValue() == 0, "MCP process exit: " + Files.readString(errors));
                check(output.readLine() == null, "Unexpected extra stdout after last response");
                check(Files.readString(errors).isEmpty(), Files.readString(errors));
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
                output.close();
                reader.shutdownNow();
            }
        }
    }

    static void error(Map<String,Object> response, int code) {
        Map<String,Object> error = object(response.get("error"));
        check(error.get("code").equals(number(code)) && error.get("message") instanceof String, response);
    }
    static void text(Map<String,Object> response, String expected, boolean failed) {
        Map<String,Object> result = object(response.get("result"));
        List<Object> content = Json.array(result.get("content"));
        check(content.size() == 1 && object(content.getFirst()).get("type").equals("text"), response);
        check(object(content.getFirst()).get("text").equals(expected) && result.get("isError").equals(failed), response);
    }
    static Map<String,Object> call(Client client, String name, Object arguments) throws Exception {
        Map<String,Object> params = new LinkedHashMap<>();
        params.put("name", name); params.put("arguments", arguments);
        return client.request("tools/call", params);
    }
    static void session(Client client, List<String> dialogue) throws Exception {
        check(object(client.request("ping", Map.of()).get("result")).isEmpty(), "ping before initialization");
        error(client.request("server/discover", Map.of()), -32601); // Modern clients can detect the legacy server.
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        error(client.request("tools/list", Map.of()), -32000);
        error(client.request("initialize", Map.of()), -32602);
        client.rawError("{", -32700);
        client.rawError("[]", -32600);
        client.rawError("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}", -32600);
        client.rawError("{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}", -32600);
        client.rawError("{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"ping\"}", -32600);
        client.rawError("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}", -32600);
        client.send(dialogue.get(0));
        Map<String,Object> initialized = client.receive();
        check(initialized.get("id").equals(number(1)), initialized);
        Map<String,Object> result = object(initialized.get("result"));
        check(result.get("protocolVersion").equals("2025-11-25"), result);
        check(object(result.get("capabilities")).keySet().equals(Set.of("tools")), result);
        check(object(result.get("serverInfo")).get("name").equals("juno-mcp-example"), result);
        error(client.request("tools/list", Map.of()), -32000); // The initialized notification is still required.
        client.send(dialogue.get(1));
        client.send(dialogue.get(2));
        Map<String,Object> listed = client.receive();
        check(listed.get("id").equals(number(2)), listed);
        List<Object> tools = Json.array(object(listed.get("result")).get("tools"));
        check(tools.size() == 2, tools);
        for (int i = 0; i < tools.size(); i++) {
            Map<String,Object> tool = object(tools.get(i)), schema = object(tool.get("inputSchema"));
            check(tool.get("name").equals(i == 0 ? "greet" : "sum"), tool);
            check(schema.get("type").equals("object") && schema.get("additionalProperties").equals(false), schema);
            check(Json.array(schema.get("required")).equals(i == 0 ? List.of("name") : List.of("a", "b")), schema);
        }
        client.send(dialogue.get(3));
        Map<String,Object> greeting = client.receive();
        check(greeting.get("id").equals(number(3)), greeting);
        text(greeting, "Привет, мир!", false);
        client.send(dialogue.get(4));
        Map<String,Object> sum = client.receive();
        check(sum.get("id").equals(number(4)), sum);
        text(sum, "0.3", false);

        text(call(client, "sum", Map.of("a", new BigDecimal("12345678901234567890.12345678"), "b", new BigDecimal("0.00000001"))),
            "12345678901234567890.12345679", false);
        text(call(client, "greet", Map.of("name", "ё😀\n\"мир\"")), "Привет, ё😀\n\"мир\"!", false);
        for (Object args : List.of(Map.of(), Map.of("a", "1", "b", number(2)), Map.of("a", true, "b", number(2)),
                Map.of("a", number(1), "b", number(2), "extra", number(3)))) {
            text(call(client, "sum", args), "Передайте только a и b: два числа", true);
        }
        text(call(client, "greet", Map.of("name", "")), "Передайте только name: непустую строку", true);
        error(call(client, "greet", null), -32602);
        error(call(client, "missing", Map.of()), -32602);
        error(call(client, "SUM", Map.of("a", number(1), "b", number(2))), -32602);
        error(client.request("missing", Map.of()), -32601);
        client.send("{\"jsonrpc\":\"2.0\",\"id\":\"bad-params\",\"method\":\"tools/call\",\"params\":[]}");
        Map<String,Object> badParams = client.receive();
        check(badParams.get("id").equals("bad-params"), badParams);
        error(badParams, -32602);
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":999}}");
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"unknown-notification\"}");
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"greet\"}}");
        client.send("{\"jsonrpc\":\"2.0\",\"id\":\"после уведомлений\",\"method\":\"ping\"}");
        Map<String,Object> ping = client.receive();
        check(ping.get("id").equals("после уведомлений") && object(ping.get("result")).isEmpty(), ping);
        client.rawError("not json", -32700);
        text(call(client, "sum", Map.of("a", number(-1), "b", number(1))), "0", false);
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("juno-mcp-");
        try {
            List<String> dialogue = Files.readAllLines(Path.of("examples/mcp/requests.jsonl"));
            Path source = Files.copy(Path.of("examples/mcp/Программа.bsl"), tmp.resolve("Программа.bsl"));
            Path jar = tmp.resolve("mcp-server.jar");
            var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
            int code = Main.execute(new String[]{"build", source.toString(), "--output", jar.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
            check(code == 0 && Files.isRegularFile(jar) && err.size() == 0, out.toString() + err);
            try (Client client = new Client(tmp, List.of(JAVA, "-jar", Path.of("build/juno.jar").toAbsolutePath().toString(), "run", source.toString()), "cli")) {
                session(client, dialogue);
            }
            Files.delete(source);
            try (Client client = new Client(tmp, List.of(JAVA, "-jar", jar.toString()), "jar")) {
                session(client, dialogue);
            }
            System.out.println("MCP example PASS");
        } finally {
            try (var paths = Files.walk(tmp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
