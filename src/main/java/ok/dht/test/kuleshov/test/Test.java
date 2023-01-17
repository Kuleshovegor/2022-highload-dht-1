package ok.dht.test.kuleshov.test;

import ok.dht.ServiceConfig;
import ok.dht.test.kuleshov.Service;
import one.nio.util.Hash;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Test {
    private static final String LOCALHOST = "http://localhost:";
    private static final String KEY = "412314";
    private static final String VALUE = "1231230";
    private final HttpClient client = HttpClient.newHttpClient();

    private static List<Service> createServices(List<Integer> ports) throws IOException {
        ports.sort(Comparator.naturalOrder());
        List<String> cluster = new ArrayList<>(ports.size());
        for (int port : ports) {
            cluster.add(LOCALHOST + port);
        }
        List<Service> services = new ArrayList<>(ports.size());
        for (int i = 0; i < ports.size(); i++) {
            Path workingDir = Files.createTempDirectory("service" + ports.get(i));

            ServiceConfig config = new ServiceConfig(ports.get(i), cluster.get(i), cluster, workingDir);
            Service service = new Service(config);

            services.add(service);
        }
        for (Service service : services) {
            try {
                service.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        return services;
    }

    private static Service createAddedService(
            int port,
            int number,
            String configRequestUrl,
            List<Integer> customHashes
    ) throws IOException {
        List<String> localUrlList = new ArrayList<>();
        localUrlList.add(LOCALHOST + port);

        Path workingDir = Files.createTempDirectory("service" + number);

        ServiceConfig config = new ServiceConfig(port, LOCALHOST + port, localUrlList, workingDir);
        Service service = new Service(config);

        try {
            service.startAdded(configRequestUrl, customHashes);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        return service;
    }

    private int sendGet(Service service, String id) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder()
                                .GET()
                                .uri(URI
                                        .create(service.getConfig().selfUrl() + "/v0/entity?id=" + id + "&ack=1&from=1")
                                )
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    private int sendPut(Service service, String id, byte[] body) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder()
                                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                                .uri(URI
                                        .create(service.getConfig().selfUrl() + "/v0/entity?id=" + id + "&ack=1&from=1")
                                )
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    public void twoNode() throws IOException, InterruptedException {
        List<Integer> ports = new ArrayList<>();
        ports.add(19234);
        List<Service> serviceList = createServices(ports);
        Service service = serviceList.get(0);
        assert (
                HttpURLConnection.HTTP_CREATED == sendPut(service, KEY, VALUE.getBytes(StandardCharsets.UTF_8))
        );
        Service addedService = createAddedService(19666, 2, LOCALHOST + 19234, new ArrayList<>());
        assert (
                HttpURLConnection.HTTP_OK == sendGet(service, KEY)
        );
        assert (
                HttpURLConnection.HTTP_OK == sendGet(addedService, KEY)
        );
        assert (200 == addedService.handleGet(KEY).getStatus());
    }

    public void twoNodeCustomHash() throws IOException, InterruptedException {
        List<Integer> ports = new ArrayList<>();
        String key2 = "4123140";
        assert (1669446702 == Hash.murmur3(key2));
        ports.add(19236);
        List<Service> serviceList = createServices(ports);
        Service service = serviceList.get(0);
        for (int i = 0; i < 1000; i++) {
            assert (
                    HttpURLConnection.HTTP_CREATED
                            == sendPut(service, KEY + i, VALUE.getBytes(StandardCharsets.UTF_8)));
        }

        assert (200 == sendGet(service, key2));
        List<Integer> hashes = new ArrayList<>();
        hashes.add(1669446702);
        Service addedService = createAddedService(19668, 6,LOCALHOST + 19236, hashes);
        assert (
                HttpURLConnection.HTTP_OK == sendGet(addedService, key2)
        );
        assert (
                200 == addedService.handleGet(key2).getStatus()
        );
    }

    public void twoNodeDelete() throws IOException, InterruptedException {
        List<Integer> ports = new ArrayList<>();
        String key2 = "4123140";
        assert (1669446702 == Hash.murmur3(key2));
        ports.add(19240);
        ports.add(19241);
        List<Service> serviceList = createServices(ports);
        for (int i = 0; i < 1000; i++) {
            assert (
                    HttpURLConnection.HTTP_CREATED
                            == sendPut(serviceList.get(0), KEY + i, VALUE.getBytes(StandardCharsets.UTF_8))
            );
        }
        assert (
                HttpURLConnection.HTTP_OK
                        == client.send(
                        HttpRequest.newBuilder()
                                .GET()
                                .uri(URI.create(LOCALHOST + ports.get(1) + "/v0/maindeletenode"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                ).statusCode()
        );

        Thread.sleep(10000);
        for (int i = 0; i < 1000; i++) {
            assert (
                    HttpURLConnection.HTTP_OK == sendGet(serviceList.get(0), KEY + i)
            );
        }
    }
}
