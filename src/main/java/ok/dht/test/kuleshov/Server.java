package ok.dht.test.kuleshov;

import ok.dht.ServiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Server {
    private static final String TMP_DIRECTORY_PREFIX = "server";
    private static final Logger log = LoggerFactory.getLogger(Server.class);

    private Server() {

    }

    public static void main(String[] args) {
        Map<String, List<String>> options = new HashMap<>();

        if (args.length == 0) {
            throw new IllegalArgumentException("arguments is empty");
        }

        if (args[0].charAt(0) != '-') {
            throw new IllegalArgumentException(
                    "arguments parameters should start with -, but found " + args[0].charAt(0)
            );
        }

        String last = "";
        for (String arg : args) {
            if (arg.length() >= 2 && arg.charAt(0) == '-' && Character.isLetter(arg.charAt(1))) {
                options.put(arg, new ArrayList<>());
                last = arg;
            } else {
                options.get(last).add(arg);
            }
        }

        int localPort = Integer.parseInt(options.get("-p").get(0));
        boolean isAdded = options.containsKey("-a");
        String url = "http://localhost:" + localPort;

        List<Integer> customHashes = new ArrayList<>();
        if (options.containsKey("-h")) {
            customHashes.addAll(options.get("-h").stream().map(Integer::parseInt).toList());
        }

        Path tmpDirectory;
        try {
            tmpDirectory = Files.createTempDirectory(TMP_DIRECTORY_PREFIX);
        } catch (IOException e) {
            log.error("Error creating temp directory " + TMP_DIRECTORY_PREFIX + ": " + e.getMessage());
            return;
        }

        List<String> localUrlList = new ArrayList<>();
        localUrlList.add(url);

        ServiceConfig cfg = new ServiceConfig(
                localPort,
                url,
                localUrlList,
                tmpDirectory
        );
        Service service = new Service(cfg);
        CompletableFuture<?> completableFuture;

        try {
            if (isAdded) {
                if (!options.containsKey("-u")) {
                    throw new IllegalArgumentException("In added mode need option -u");
                }
                completableFuture = service.startAdded(options.get("-u").get(0), customHashes);
            } else {
                completableFuture = service.start();
            }
        } catch (IOException e) {
            log.error("Error starting service: " + e.getMessage());
            return;
        }

        try {
            completableFuture.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            log.error("Error starting service: " + e.getMessage());
            return;
        }

        log.info("Socket is ready: " + url);
    }
}
