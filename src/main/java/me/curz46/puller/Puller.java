package me.curz46.puller;

import com.google.gson.Gson;
import io.undertow.Undertow;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.PathTemplateMatch;
import io.undertow.util.StatusCodes;

import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;

public class Puller {

    public static void main(final String[] args) {
        final File file = new File("config.json");
        if (!file.exists()) {
            try {
                Files.copy(
                    Puller.class.getClassLoader().getResourceAsStream("config.example.json"),
                    file.toPath()
                );
            } catch (final IOException e) {
                throw new RuntimeException("Failed to write example to 'config.json': ", e);
            }

            System.out.println("'config.json' does not exist. Written example configuration.");
            return;
        }

        final Options options;
        try (final FileReader reader = new FileReader("config.json")) {
            options = (new Gson()).fromJson(reader, Options.class);
        } catch (final IOException e) {
            throw new RuntimeException("Failed to read options from config.json: ", e);
        }

        final RoutingHandler routingHandler = (new RoutingHandler());
        for (final Hook hook : options.hooks) {
            routingHandler.post(
                "pull/" + hook.name + "/with/key/{key}",
                ex -> {
                    final String key = ex.getAttachment(PathTemplateMatch.ATTACHMENT_KEY)
                        .getParameters()
                        .get("key");
                    if (!key.equals(hook.key)) {
                        System.out.println("Received request with invalid key.");
                        ex.setStatusCode(StatusCodes.UNAUTHORIZED);
                        ex.getResponseSender().close();
                    } else {
                        ex.startBlocking();

                        final Request request = (new Gson()).fromJson(
                            new BufferedReader(new InputStreamReader(ex.getInputStream())),
                            Request.class
                        );
                        if (request == null || request.ref == null) {
                            System.out.println("'ref' property not provided in POST data; assuming not a push");
                        } else {
                            final String[] parts = request.ref.split("/");
                            final String branch = parts[parts.length - 1];
                            if (hook.branches.length != 0 && !Arrays.asList(hook.branches).contains(branch)) {
                                System.out.println("Received request with undefined branch.");
                                return;
                            }
                            System.out.println("Received request with defined branch.");
                            System.out.println("Executing commands for hook: " + hook.name);
                            final Runtime runtime = Runtime.getRuntime();
                            for (final String command : hook.commands) {
                                runtime.exec(command);
                            }
                            System.out.println("Execution finished for hook: " + hook.name);
                        }

                        ex.setStatusCode(StatusCodes.ACCEPTED);
                        ex.getResponseSender().close();
                    }
                }
            );
        }

        (Undertow.builder()
            .addHttpListener(options.port, options.host)
            .setHandler(new BlockingHandler(routingHandler))
            .build()
        ).start();
        System.out.println("Listening on " + options.host + ":" + options.port);
    }

    public class Options {

        private String host;
        private int port;
        private Hook[] hooks;

    }

    public class Hook {

        private String name;
        private String key;
        private String[] branches = new String[] {};
        private String[] commands = new String[] {};

    }

    public class Request {

        private String ref;

    }

}
