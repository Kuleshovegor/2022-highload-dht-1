package ok.dht.test.kuleshov.sharding;

import jdk.incubator.foreign.MemorySegment;
import ok.dht.test.kuleshov.dao.Entry;
import ok.dht.test.kuleshov.exception.TransferException;
import one.nio.serial.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class TransferSenderService extends TransferService {
    private final String selfUrl;
    private final List<Shard> shards = new ArrayList<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<HashRange, Shard> hashRangeShardMap = new HashMap<>();
    private final Logger log = LoggerFactory.getLogger(TransferSenderService.class);

    public TransferSenderService(String selfUrl, Function<String, Integer> hashFunction) {
        super(hashFunction);
        this.selfUrl = selfUrl;
    }

    public void setTransfer(Map<Shard, Set<HashRange>> shardSetMap) {
        if (isTransferring) {
            throw new TransferException("Transfer is started");
        }
        isTransferring = true;

        shards.addAll(shardSetMap.keySet());

        for (Map.Entry<Shard, Set<HashRange>> entry : shardSetMap.entrySet()) {
            hashRanges.addAll(entry.getValue());
            for (HashRange hashRange : entry.getValue()) {
                hashRangeShardMap.put(hashRange, entry.getKey());
                circle.add(hashRange.getLeftBorder());
                circle.add(hashRange.getRightBorder());
            }
        }
    }

    public void startTransfer(Iterator<Entry<MemorySegment>> entryIterator) {
        log.info("Start transfer");
        while (entryIterator.hasNext()) {
            Entry<MemorySegment> entry = entryIterator.next();

            String id = new String(entry.key().toByteArray(), StandardCharsets.UTF_8);

            if (isInTransfer(id)) {
                sendTransfer(id, entry.value().toByteArray());
            }
        }

        sendTransferEnd();

        clear();

        log.info("End transfer");
    }

    private void sendTransfer(String key, byte[] value) {
        Shard shard = hashRangeShardMap.get(getHashRange(key));
        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    createTransferRequest(shard, key, value),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        } catch (IOException | InterruptedException e) {
            clear();
            throw new TransferException("error transferring key: " + key, e);
        }

        if (response.statusCode() != 201) {
            clear();
            throw new TransferException("error transferring key: " + key + ", status: " + response.statusCode());
        }
    }

    private void sendTransferEnd() {
        for (Shard shard : shards) {
            HttpResponse<String> response;
            try {
                response = httpClient.send(
                        createTransferEndRequest(shard),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
            } catch (IOException | InterruptedException e) {
                throw new TransferException("error end transferring error", e);
            }

            if (response.statusCode() != 200) {
                throw new TransferException("error transferring end, status: " + response.statusCode());
            }
        }
    }

    private HttpRequest createTransferEndRequest(Shard shard) {
        String jsonShard;
        try {
            jsonShard = Json.toJson(new ShardAddBody(selfUrl, new ArrayList<>()));
        } catch (IOException e) {
            throw new TransferException("error creating json from shard.", e);
        }

        return HttpRequest.newBuilder()
                .PUT(HttpRequest.BodyPublishers.ofString(jsonShard))
                .timeout(Duration.ofSeconds(2))
                .uri(URI.create(shard.getUrl() + "/v0/transend"))
                .build();
    }

    private HttpRequest createTransferRequest(Shard shard, String key, byte[] value) {
        return HttpRequest.newBuilder()
                .PUT(HttpRequest.BodyPublishers.ofByteArray(value))
                .timeout(Duration.ofSeconds(2))
                .uri(URI.create(shard.getUrl() + "/v0/transfer/entity?id=" + key))
                .build();
    }

    @Override
    public void clear() {
        super.clear();
        shards.clear();
    }
}
