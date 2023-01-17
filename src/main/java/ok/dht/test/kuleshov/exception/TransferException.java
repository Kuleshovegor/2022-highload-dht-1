package ok.dht.test.kuleshov.exception;

public class TransferException extends RuntimeException {
    public TransferException(String message, Throwable cause) {
        super(message, cause);
    }

    public TransferException(String message) {
        super(message);
    }
}
