package ok.dht.test.kuleshov;

import ok.dht.test.kuleshov.exception.TransferException;
import ok.dht.test.kuleshov.sharding.ClusterConfig;
import ok.dht.test.kuleshov.sharding.ConsistentHashService;
import ok.dht.test.kuleshov.sharding.HashRange;
import ok.dht.test.kuleshov.sharding.Shard;
import ok.dht.test.kuleshov.sharding.ShardAddBody;
import ok.dht.test.kuleshov.sharding.TransferReceiverService;
import ok.dht.test.kuleshov.sharding.TransferSenderService;
import ok.dht.test.kuleshov.utils.RequestsUtils;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.serial.Json;
import one.nio.server.RejectedSessionException;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import static ok.dht.test.kuleshov.Validator.isCorrectAckFrom;
import static ok.dht.test.kuleshov.Validator.isCorrectId;
import static ok.dht.test.kuleshov.utils.RequestsUtils.getTimestampHeader;
import static ok.dht.test.kuleshov.utils.ResponseUtils.emptyResponse;

public class CoolAsyncHttpServer extends CoolHttpServer {
    private static final String TIMESTAMP_HEADER = "timestamp";
    private static final String ERROR_RESPONSE_SENDING = "Error sending response to client: ";
    private static final String ERROR_SENDING_ERROR = "Error sending error to client: ";
    private static final int DEFAULT_VNODES = 4;
    private static final Function<String, Integer> DEFAULT_HASH_FUNCTION = Hash::murmur3;

    private static final int WORKER_CORE_POOL_SIZE = 4;
    private static final int WORKER_MAXIMUM_POOL_SIZE = 4;
    private static final int SENDER_CORE_POOL_SIZE = 4;
    private static final int SENDER_MAXIMUM_POOL_SIZE = 4;

    private int defaultFrom;
    private int defaultAck;
    private final String selfUrl;
    private final ReentrantReadWriteLock clusterConfigLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readClusterLock = clusterConfigLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeClusterLock = clusterConfigLock.writeLock();
    private ExecutorService workerExecutorService;
    private final ConsistentHashService consistentHashService;
    private final TransferSenderService transferSenderService;
    private final TransferReceiverService transferReceiverService;
    private final String configRequestUrl;
    private final List<Integer> customHashes;
    private final List<String> clusterUrls;
    private final boolean isAddedNode;
    private HttpClient httpClient = HttpClient.newHttpClient();
    private final Logger log = LoggerFactory.getLogger(CoolAsyncHttpServer.class);

    public CoolAsyncHttpServer(
            HttpServerConfig config,
            boolean isAddedNode,
            List<Integer> customHashes,
            String configRequestUrl,
            Service service,
            Object... routers
    ) throws IOException {
        super(config, service, routers);

        selfUrl = service.getConfig().selfUrl();
        transferSenderService = new TransferSenderService(selfUrl, DEFAULT_HASH_FUNCTION);
        transferReceiverService = new TransferReceiverService(DEFAULT_HASH_FUNCTION);
        this.clusterUrls = new ArrayList<>(service.getConfig().clusterUrls());
        clusterUrls.sort(Comparator.naturalOrder());
        consistentHashService = new ConsistentHashService(DEFAULT_HASH_FUNCTION);

        this.isAddedNode = isAddedNode;
        this.configRequestUrl = configRequestUrl;
        this.customHashes = customHashes;
    }

    private ClusterConfig requestClusterConfig(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(url + "/v0/cluster-config"))
                .timeout(Duration.ofSeconds(2))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("error sending  url: " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "error response add node url: " + url + ", error: " + response.statusCode()
            );
        }

        ClusterConfig config;
        try {
            config = Json.fromJson(response.body(), ClusterConfig.class);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return config;
    }

    private void sendAddNode(String url, List<Integer> hashes) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .PUT(HttpRequest.BodyPublishers.ofString(Json.toJson(new ShardAddBody(selfUrl, hashes))))
                .uri(URI.create(url + "/v0/addnode"))
                .timeout(Duration.ofSeconds(100))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("error sending add node url: " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "error response add node url: " + url + ", error: " + response.statusCode()
            );
        }
    }

    private void sendDeleteNode(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .PUT(HttpRequest.BodyPublishers.ofString(Json.toJson(new ShardAddBody(selfUrl, new ArrayList<>()))))
                .uri(URI.create(url + "/v0/deletenode"))
                .timeout(Duration.ofSeconds(2))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("error sending delete node url: " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "error response delete node url: " + url + ", error: " + response.statusCode()
            );
        }
    }

    @Override
    public synchronized void start() {
        workerExecutorService = new ThreadPoolExecutor(WORKER_CORE_POOL_SIZE,
                WORKER_MAXIMUM_POOL_SIZE,
                100,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(128)
        );

        ExecutorService senderExecutorService = new ThreadPoolExecutor(SENDER_CORE_POOL_SIZE,
                SENDER_MAXIMUM_POOL_SIZE,
                100,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()
        );

        httpClient = HttpClient
                .newBuilder()
                .executor(senderExecutorService)
                .build();

        writeClusterLock.lock();
        try {
            super.start();

            ClusterConfig clusterConfig;
            if (isAddedNode) {
                try {
                    clusterConfig = requestClusterConfig(configRequestUrl);
                } catch (IOException e) {
                    throw new IllegalStateException("Cluster config request error", e);
                }
                clusterConfig.getUrlToHash().put(selfUrl, customHashes);
                for (Map.Entry<String, List<Integer>> entry : clusterConfig.getUrlToHash().entrySet()) {
                    if (entry.getKey().equals(selfUrl)) {
                        continue;
                    }
                    if (entry.getValue() == null || entry.getValue().isEmpty()) {
                        consistentHashService.addShard(new Shard(entry.getKey()), DEFAULT_VNODES);
                    } else {
                        consistentHashService.addShard(new Shard(entry.getKey()), new ArrayList<>(entry.getValue()));
                    }
                }
                Map<Shard, Set<HashRange>> shardSetMap;
                if (clusterConfig.getUrlToHash().get(selfUrl) == null
                        || clusterConfig.getUrlToHash().get(selfUrl).isEmpty()) {
                    shardSetMap = consistentHashService.addShard(new Shard(selfUrl), DEFAULT_VNODES);
                } else {
                    shardSetMap = consistentHashService.addShard(
                            new Shard(selfUrl), new ArrayList<>(clusterConfig.getUrlToHash().get(selfUrl))
                    );
                }
                transferReceiverService.receiveTransfer(shardSetMap);

                defaultFrom = clusterConfig.getUrlToHash().size();
                defaultAck = defaultFrom / 2 + 1;
                writeClusterLock.unlock();
                for (String shard : clusterConfig.getUrlToHash().keySet()) {
                    if (!Objects.equals(shard, selfUrl)) {
                        try {
                            sendAddNode(shard, new ArrayList<>(clusterConfig.getUrlToHash().get(selfUrl)));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } else {
                for (String shard : clusterUrls) {
                    consistentHashService.addShard(new Shard(shard), DEFAULT_VNODES);
                }
                defaultFrom = clusterUrls.size();
                defaultAck = defaultFrom / 2 + 1;
            }
        } finally {
            if (writeClusterLock.isHeldByCurrentThread()) {
                writeClusterLock.unlock();
            }
        }

    }

    private void handleGetClusterConfig(Request request, HttpSession session) throws IOException {
        if (request.getMethod() != Request.METHOD_GET) {
            session.sendResponse(emptyResponse(Response.BAD_REQUEST));

            return;
        }
        ClusterConfig clusterConfig = consistentHashService.getClusterConfig();
        Response response = new Response(Response.OK, Json.toJson(clusterConfig).getBytes(StandardCharsets.UTF_8));

        session.sendResponse(response);
    }

    private void handleClusterChange(boolean isAdd, Request request, HttpSession session) throws IOException {
        String body = new String(request.getBody(), StandardCharsets.UTF_8);

        ShardAddBody shardAddBody;
        try {
            shardAddBody = Json.fromJson(body, ShardAddBody.class);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }

        writeClusterLock.lock();
        try {
            Map<Shard, Set<HashRange>> map;
            if (isAdd) {
                if (shardAddBody.getHashes() == null || shardAddBody.getHashes().isEmpty()) {
                    map = consistentHashService.addShard(new Shard(shardAddBody.getUrl()), DEFAULT_VNODES);
                } else {
                    map = consistentHashService.addShard(new Shard(shardAddBody.getUrl()), shardAddBody.getHashes());
                }
            } else {
                map = consistentHashService.removeShard(new Shard(shardAddBody.getUrl()));
            }
            Shard selfShard = new Shard(selfUrl);
            Set<HashRange> hashRangeSet = map.get(selfShard);

            defaultFrom = consistentHashService.clusterSize();
            defaultAck = defaultFrom / 2 + 1;

            if (isAdd) {
                if (hashRangeSet != null) {
                    transferSenderService.setTransfer(Map.of(new Shard(shardAddBody.getUrl()), hashRangeSet));
                    writeClusterLock.unlock();
                    transferSenderService.startTransfer(service.getAll());
                }
            } else {
                if (hashRangeSet != null) {
                    transferReceiverService.receiveTransfer(Map.of(new Shard(shardAddBody.getUrl()), hashRangeSet));
                }
            }
        } finally {
            if (writeClusterLock.isHeldByCurrentThread()) {
                writeClusterLock.unlock();
            }
        }

        session.sendResponse(emptyResponse(Response.OK));
    }

    private void handleMainDeleteShardRequest(HttpSession session) throws IOException {
        writeClusterLock.lock();
        try {
            Map<Shard, Set<HashRange>> map = consistentHashService.removeShard(new Shard(selfUrl));

            session.sendResponse(emptyResponse(Response.OK));

            for (Shard shard : map.keySet()) {
                sendDeleteNode(shard.getUrl());
            }

            transferSenderService.setTransfer(map);
        } finally {
            writeClusterLock.unlock();
        }
        transferSenderService.startTransfer(service.getAll());
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        workerExecutorService.execute(() -> {
            readClusterLock.lock();
            try {
                int method = request.getMethod();
                if (!SUPPORTED_METHODS.contains(method)) {
                    session.sendResponse(emptyResponse(Response.METHOD_NOT_ALLOWED));

                    return;
                }

                String path = request.getPath();
                switch (path) {
                    case "/v0/cluster-config" -> {
                        handleGetClusterConfig(request, session);
                        return;
                    }
                    case "/v0/addnode" -> {
                        readClusterLock.unlock();
                        handleClusterChange(true, request, session);
                        return;
                    }
                    case "/v0/deletenode" -> {
                        readClusterLock.unlock();
                        handleClusterChange(false, request, session);
                        return;
                    }
                    case "/v0/maindeletenode" -> {
                        readClusterLock.unlock();
                        handleMainDeleteShardRequest(session);
                        return;
                    }

                    case "/v0/transend" -> {
                        transferReceiverService.receiveEnd(new Shard(
                                Json.fromJson(
                                        new String(request.getBody(), StandardCharsets.UTF_8),
                                        ShardAddBody.class
                                ).getUrl()));
                        session.sendResponse(emptyResponse(Response.OK));
                        return;
                    }
                    case "/v0/entities" -> {
                        handleRangeRequest(request, session);
                        return;
                    }
                    default -> {
                        //continue
                    }
                }

                String id = request.getParameter("id=");
                if (!isCorrectId(id)) {
                    session.sendResponse(emptyResponse(Response.BAD_REQUEST));

                    return;
                }

                if ("/v0/transfer/entity".equals(path)) {
                    Response resp = service.handle(method, id, request, getTimestampHeader(request));
                    session.sendResponse(resp);
                    return;
                }

                if (transferSenderService.isInTransfer(id) || transferReceiverService.isInTransfer(id)) {
                    session.sendResponse(emptyResponse(Response.SERVICE_UNAVAILABLE));
                    return;
                }

                switch (path) {
                    case "/v0/entity" -> handleRequest(id, request, session);
                    case "/master/v0/entity" -> {
                        Response resp = service.handle(method, id, request, getTimestampHeader(request));
                        session.sendResponse(resp);
                    }
                    default -> session.sendResponse(emptyResponse(Response.BAD_REQUEST));
                }
            } catch (TransferException e) {
                try {
                    session.sendResponse(emptyResponse(Response.GATEWAY_TIMEOUT));
                } catch (IOException exception) {
                    log.error(ERROR_SENDING_ERROR + exception.getMessage());
                    session.close();
                }
            } catch (Exception e) {
                log.error(ERROR_RESPONSE_SENDING);
                try {
                    session.sendResponse(emptyResponse(Response.BAD_REQUEST));
                } catch (IOException exception) {
                    log.error(ERROR_SENDING_ERROR + exception.getMessage());
                    session.close();
                }
            } finally {
                try {
                    readClusterLock.unlock();
                } catch (IllegalMonitorStateException ignore) {
                    //ignore
                }
            }
        });
    }

    @Override
    public synchronized void stop() {
        terminateExecutor(workerExecutorService);

        super.stop();
    }

    private void handleRangeRequest(Request request, HttpSession session) {
        String start = request.getParameter("start=");
        String end = request.getParameter("end=");
        if (start == null || start.isBlank() || (end != null && end.isBlank())) {
            try {
                session.sendResponse(emptyResponse(Response.BAD_REQUEST));
            } catch (IOException e) {
                log.error(ERROR_RESPONSE_SENDING + e.getCause());
                try {
                    session.sendResponse(emptyResponse(Response.SERVICE_UNAVAILABLE));
                } catch (IOException exception) {
                    log.error(ERROR_SENDING_ERROR + exception.getMessage());
                    session.close();
                }
            }

            return;
        }

        try {
            Iterator<byte[]> chunkIterator = new ChunkIterator(service.getRange(start, end));

            session.sendResponse(createChunkedHeaderResponse());
            ((CoolSession) session).writeChunks(chunkIterator);
        } catch (IOException e) {
            log.error(ERROR_RESPONSE_SENDING + e.getCause());
            e.printStackTrace();
            try {
                session.sendResponse(emptyResponse(Response.BAD_REQUEST));
            } catch (IOException exception) {
                log.error(ERROR_SENDING_ERROR + exception.getMessage());
                session.close();
            }
        }

    }

    public static Response createChunkedHeaderResponse() {
        Response response = new Response(Response.OK);
        response.addHeader("Transfer-Encoding: chunked");

        return response;
    }

    @Override
    public HttpSession createSession(Socket socket) throws RejectedSessionException {
        return new CoolSession(socket, this);
    }

    private void handleRequest(String id, Request request, HttpSession session) {
        Integer parseFrom = RequestsUtils.parseInt(request.getParameter("from="));
        Integer parseAck = RequestsUtils.parseInt(request.getParameter("ack="));

        int from = parseFrom == null ? defaultFrom : parseFrom;
        int ack = parseAck == null ? defaultAck : parseAck;

        if (!isCorrectAckFrom(ack, from, consistentHashService.clusterSize())) {
            try {
                session.sendResponse(emptyResponse(Response.BAD_REQUEST));
            } catch (IOException exception) {
                log.error(exception.getMessage());
                session.close();
            }
            return;
        }

        SlaveResponseHandler slaveResponseHandler = new SlaveResponseHandler(ack, from, session);
        long timestamp = System.currentTimeMillis();

        boolean isSelf = false;

        final List<Shard> shards = consistentHashService.getShardsByKey(id, from);

        for (Shard shard : shards) {
            String slaveUrl = shard.getUrl();

            if (slaveUrl.equals(selfUrl)) {
                isSelf = true;
                continue;
            }

            HttpRequest requestToSlave = createRequestToSlave(request, slaveUrl, timestamp);
            httpClient.sendAsync(requestToSlave,
                    HttpResponse.BodyHandlers.ofByteArray()
            ).whenComplete((response, exception) -> {
                if (exception == null) {
                    slaveResponseHandler.handleResponse(request.getMethod(), HandleResponse.fromHttpResponse(response));
                } else {
                    log.error("Error slave's response" + exception.getMessage());
                    slaveResponseHandler.handleFrom();
                }
            });
        }

        if (isSelf) {
            Response selfResponse = service.handle(request.getMethod(), id, request, timestamp);
            slaveResponseHandler.handleResponse(request.getMethod(), HandleResponse.fromOneResponse(selfResponse));
        }
    }

    private HttpRequest createRequestToSlave(Request request, String url, long timestamp) {
        return HttpRequest.newBuilder()
                .method(request.getMethodName(), HttpRequest.BodyPublishers.ofByteArray(
                        request.getBody() == null ? new byte[0] : request.getBody()
                ))
                .timeout(Duration.of(2, ChronoUnit.SECONDS))
                .header(TIMESTAMP_HEADER, String.valueOf(timestamp))
                .uri(URI.create(url + "/master" + request.getURI()))
                .build();
    }

    private static void terminateExecutor(ExecutorService executorService) {
        boolean isFinished = false;
        executorService.shutdown();

        try {
            isFinished = executorService.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (isFinished) {
            executorService.shutdownNow();
        }

    }
}
