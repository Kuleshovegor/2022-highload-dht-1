package ok.dht.test.kuleshov.sharding;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class ClusterConfig implements Serializable {
    private Map<String, List<Integer>> urlToHash;

    public Map<String, List<Integer>> getUrlToHash() {
        return urlToHash;
    }

    public void setUrlToHash(Map<String, List<Integer>> urlToHash) {
        this.urlToHash = urlToHash;
    }
}
