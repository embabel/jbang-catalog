///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Embabel for Spring: the Embabel appliance as a plain Java process, with the Spring realms.
 *
 * It downloads the stock appliance jar once, makes a data folder with a user and an MCP token, and
 * starts the appliance on localhost. Nothing is installed anywhere else.
 *
 *   jbang spring@embabel [--port 7042] [--data <folder>] [--world <git URL or owner/repo>]
 *
 * EMBABEL_PORT, EMBABEL_DATA_DIR and EMBABEL_WORLD do the same as the three options.
 */
public class spring {

    /* The appliance build this launcher starts, and where its two jars are published. */
    static final String BUILD = "sha-6c7e628";
    static final String DOWNLOADS = "https://github.com/embabel/jbang-catalog/releases/download/appliance-" + BUILD + "/";
    static final String APPLIANCE_JAR = "assistant.jar";
    static final String APPLIANCE_SHA256 = "38f2d23109f1b5cb7aa4c369ba69a4362671b87942a7279854b01cf55b3ad9b0";
    static final String CONSOLE_JAR = "worlds-console-static.jar";
    static final String CONSOLE_SHA256 = "245847a4153682b82cac4cb196f5b178e224c492c59540865d17c6c36a5aab87";

    /* The world a new user starts from: it extends embabel-worlds/world-spring, which lists the realms. */
    static final String STARTER_WORLD = "https://github.com/embabel-worlds/world-spring-starter.git";
    static final int DEFAULT_PORT = 7042;
    static final String USER = "dev";

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(option(args, "--port", System.getenv("EMBABEL_PORT"), String.valueOf(DEFAULT_PORT)));
        Path data = Path.of(option(args, "--data", System.getenv("EMBABEL_DATA_DIR"),
                Path.of(System.getProperty("user.home"), ".embabel", "spring").toString())).toAbsolutePath();

        String world = worldUrl(option(args, "--world", System.getenv("EMBABEL_WORLD"), STARTER_WORLD));
        /* The world is cloned only when the data folder has none: one that is already there is used as it is. */
        boolean existing = Files.isDirectory(data.resolve("embabel/assistant/users").resolve(USER));

        requireFree(port);
        Path app = Files.createDirectories(data.resolve("app").resolve(BUILD));
        Path plugins = Files.createDirectories(app.resolve("plugins"));
        Path run = Files.createDirectories(data.resolve("run"));
        fetch(APPLIANCE_JAR, APPLIANCE_SHA256, app.resolve(APPLIANCE_JAR));
        fetch(CONSOLE_JAR, CONSOLE_SHA256, plugins.resolve(CONSOLE_JAR));
        String token = userAndToken(run);

        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "--add-modules=jdk.incubator.vector",
                "-Duser.home=" + data,
                "-Dloader.path=" + plugins,
                "-Dserver.address=127.0.0.1",
                "-cp", app.resolve(APPLIANCE_JAR).toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher"));
        ProcessBuilder builder = new ProcessBuilder(command).directory(run.toFile()).inheritIO();
        builder.environment().put("SERVER_PORT", String.valueOf(port));
        builder.environment().put("EMBABEL_MODE", "worlds");
        builder.environment().put("ASSISTANT_BOOTSTRAP_WORLD", world);
        builder.environment().put("ASSISTANT_INFRASTRUCTURE_MCP_DEVELOPER_ENDPOINT_ENABLED", "true");
        builder.environment().put("ASSISTANT_INFRASTRUCTURE_MCP_DEFAULT_MODE", "DEVELOPER");

        System.out.println();
        System.out.println("Embabel for Spring (experimental), appliance build " + BUILD);
        System.out.println("  Data in      " + data);
        System.out.println("  World        " + (existing ? "the one already in the data folder" : "new, from " + world));
        if (existing && !world.equals(STARTER_WORLD)) {
            System.out.println("               --world is ignored: this data folder has a world. Use another --data folder to start one from " + world);
        }
        System.out.println("  Console      http://127.0.0.1:" + port + "/ui/");
        System.out.println("  MCP          http://127.0.0.1:" + port + "/mcp/code");
        System.out.println("  MCP token    " + token);
        System.out.println("  Add it to Claude Code:");
        System.out.println("    claude mcp add --transport http embabel http://127.0.0.1:" + port + "/mcp/code --header \"Authorization: Bearer " + token + "\"");
        System.out.println("  Starting; the first start also fetches the realms and an embedding model.");
        System.out.println();

        Process appliance = builder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(appliance::destroy));
        System.exit(appliance.waitFor());
    }

    /* The value after [name] on the command line, else [fromEnvironment], else [otherwise]. */
    static String option(String[] args, String name, String fromEnvironment, String otherwise) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return fromEnvironment != null && !fromEnvironment.isBlank() ? fromEnvironment : otherwise;
    }

    /* A world as a git URL: a full URL as given, `owner/repo` on GitHub. */
    static String worldUrl(String given) {
        String world = given.trim();
        if (world.contains("://") || world.startsWith("git@")) return world;
        if (world.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) return "https://github.com/" + world.replaceFirst("\\.git$", "") + ".git";
        System.err.println("--world takes a git URL or owner/repo, and was given '" + given + "'.");
        System.exit(2);
        return world;
    }

    static void requireFree(int port) {
        try (ServerSocket probe = new ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress())) {
            probe.setReuseAddress(true);
        } catch (IOException taken) {
            System.err.println("Port " + port + " is in use. Stop what holds it, or choose another with --port.");
            System.exit(2);
        }
    }

    /* Download [name] to [target] unless it is already there with the right digest. */
    static void fetch(String name, String sha256, Path target) throws Exception {
        if (Files.exists(target) && sha256.equals(digest(target))) return;
        System.out.println("Downloading " + name + " (" + BUILD + ") ...");
        Path part = target.resolveSibling(name + ".part");
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(DOWNLOADS + name)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            System.err.println("Could not download " + DOWNLOADS + name + ": HTTP " + response.statusCode());
            System.exit(3);
        }
        try (InputStream body = response.body()) {
            Files.copy(body, part, StandardCopyOption.REPLACE_EXISTING);
        }
        String got = digest(part);
        if (!sha256.equals(got)) {
            Files.deleteIfExists(part);
            System.err.println(name + " did not arrive intact (sha256 " + got + "). Run again.");
            System.exit(3);
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    static String digest(Path file) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) sha.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /* The first run makes one user and one MCP token, kept in the data folder; later runs read the token back. */
    static String userAndToken(Path run) throws IOException {
        Path users = run.resolve("users-local.yml");
        Path tokens = run.resolve("mcp-tokens-local.yml");
        if (!Files.exists(users)) {
            Files.writeString(users, "assistant:\n  users:\n    - username: " + USER + "\n      display-name: Developer\n      email: " + USER + "@localhost\n");
        }
        if (Files.exists(tokens)) {
            for (String line : Files.readAllLines(tokens)) {
                if (line.trim().startsWith("mcp-api-token:")) return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = HexFormat.of().formatHex(random);
        Files.writeString(tokens, "assistant:\n  mcp-api-token: " + token + "\n  mcp-api-token-user: " + USER + "\n");
        try {
            Files.setPosixFilePermissions(tokens, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            // Windows: the file keeps the folder's permissions.
        }
        return token;
    }
}
