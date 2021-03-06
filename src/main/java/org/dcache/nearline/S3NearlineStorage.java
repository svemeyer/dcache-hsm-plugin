package org.dcache.nearline;

import io.minio.MinioClient;
import io.minio.errors.*;
import org.apache.commons.io.FileUtils;
import org.dcache.pool.nearline.spi.FlushRequest;
import org.dcache.pool.nearline.spi.NearlineStorage;
import org.dcache.pool.nearline.spi.RemoveRequest;
import org.dcache.pool.nearline.spi.StageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlpull.v1.XmlPullParserException;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

public class S3NearlineStorage implements NearlineStorage
{
    private static final Logger _log = LoggerFactory.getLogger(S3NearlineStorage.class);

    protected final String type;
    protected final String name;

    String endpoint = "";
    String accessKey = "";
    String secretKey = "";

    private MinioClient minio;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final List<FutureTask<UUID>> taskList = new ArrayList<>();

    public S3NearlineStorage(String type, String name) {
        this.type = type;
        this.name = name;
    }

    /**
     * Flush all files in {@code requests} to nearline storage.
     *
     * @param requests
     */
    @Override
    public void flush(final Iterable<FlushRequest> requests)
    {
        for (FlushRequest fRequest: requests) {
            FutureTask<UUID> flushTask = new FutureTask<UUID>(new Callable() {
                @Override
                public UUID call() {
                    _log.info("Flush file " + fRequest.getReplicaUri().getPath());

                    String bucketName = fRequest.getFileAttributes().getStorageClass().toLowerCase()
                            .replaceAll("[^a-z-.]", ".");
                    String pnfsId = fRequest.getFileAttributes().getPnfsId().toString();
                    String source = fRequest.getReplicaUri().getPath();

                    fRequest.activate();

                    try {
                        boolean bucketExists = minio.bucketExists(bucketName);
                        if (!bucketExists) {
                            minio.makeBucket(bucketName);
                        }
                        minio.putObject(bucketName, pnfsId, source, fRequest.getFileAttributes().getSize(),
                                null, null, null);
                        fRequest.completed(Collections.singleton(new URI(type, name, '/' +
                                fRequest.getFileAttributes().getPnfsId().toString(), null, bucketName)));
                    } catch (InvalidBucketNameException | NoSuchAlgorithmException | InsufficientDataException |
                            IOException | InvalidKeyException | NoResponseException | XmlPullParserException |
                            ErrorResponseException | InternalException | InvalidResponseException |
                            RegionConflictException | InvalidArgumentException | URISyntaxException e) {
                        fRequest.failed(e);
                        _log.error("Flush file " + pnfsId + " failed: " + e);
                        return null;
                    }
                    return fRequest.getId();
                }
            });
            taskList.add(flushTask);
            CompletableFuture.runAsync(flushTask, executor);
        }

    }

    /**
     * Stage all files in {@code requests} from nearline storage.
     *
     * @param requests
     */
    @Override
    public void stage(Iterable<StageRequest> requests)
    {
        for (final StageRequest sRequest : requests
             ) {
            FutureTask<UUID> stageTask = new FutureTask<UUID>(new Callable() {
                @Override
                public UUID call() {
                    _log.info("Stage file " + sRequest.getReplicaUri());
                    sRequest.activate();
                    sRequest.allocate();

                    URI destination = sRequest.getReplicaUri();
                    String bucketName = sRequest.getFileAttributes().getStorageClass().toLowerCase()
                            .replaceAll("[^a-z-.]", ".");
                    String objectName = sRequest.getFileAttributes().getPnfsId().toString();
                    try {

                        InputStream content = minio.getObject(bucketName, objectName);
                        File target = new File(destination);
                        FileUtils.copyInputStreamToFile(content, target);
                        sRequest.completed(sRequest.getFileAttributes().getChecksumsIfPresent().isPresent() ?
                                sRequest.getFileAttributes().getChecksums() : null);
                    } catch (InvalidKeyException | NoSuchAlgorithmException | NoResponseException |
                            InvalidResponseException | XmlPullParserException | InvalidBucketNameException |
                            InvalidArgumentException | InsufficientDataException | ErrorResponseException |
                            InternalException | IOException e) {
                        sRequest.failed(e);
                        _log.error("Stage file " + destination + " failed: " + e);
                        return null;
                    }
                    return sRequest.getId();
                }
            });
            taskList.add(stageTask);
            CompletableFuture.runAsync(stageTask, executor);
        }
    }

    /**
     * Delete all files in {@code requests} from nearline storage.
     *
     * @param requests
     */
    @Override
    public void remove(final Iterable<RemoveRequest> requests)
    {
        for (RemoveRequest rRequest: requests
             ) {

            FutureTask<UUID> removeTask = new FutureTask<UUID>(new Callable() {
                @Override
                public UUID call() {
                    _log.info("Removing file " + rRequest.getUri().getPath().replace("/", ""));
                    String bucketName = rRequest.getUri().getFragment();
                    rRequest.activate();
                    try {
                        minio.removeObject(bucketName,
                                rRequest.getUri().getPath().replace("/", ""));
                        rRequest.completed(null);
                        return rRequest.getId();
                    } catch (InvalidKeyException | NoSuchAlgorithmException | NoResponseException |
                            InvalidResponseException | XmlPullParserException | InvalidBucketNameException |
                            InvalidArgumentException | InsufficientDataException | ErrorResponseException |
                            InternalException | IOException e) {
                        rRequest.failed(e);
                        _log.error("Removing file " + rRequest.getUri().getPath()
                                .replace("/", "") + " failed: " + e);
                        return null;
                    }
                }
            });
            executor.execute(removeTask);
            CompletableFuture.runAsync(removeTask, executor);
        }
    }

    /**
     * Cancel any flush, stage or remove request with the given id.
     * <p>
     * The failed method of any cancelled request should be called with a
     * CancellationException. If the request completes before it can be
     * cancelled, then the cancellation should be ignored and the completed
     * or failed method should be called as appropriate.
     * <p>
     * A call to cancel must be non-blocking.
     *
     * @param uuid id of the request to cancel
     */
    @Override
    public void cancel(UUID uuid)
    {
        for (FutureTask ft: taskList
             ) {
            String fileId = null;
            try {
                fileId = ft.get().toString();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
            if (uuid.toString().equals(fileId)) {
                _log.info("Cancelling Task with uuid " + uuid);
                ft.cancel(true);
            }
        }
    }

    /**
     * Applies a new configuration.
     *
     * @param properties
     * @throws IllegalArgumentException if the configuration is invalid
     */
    @Override
    public void configure(Map<String, String> properties) throws IllegalArgumentException {
        endpoint = properties.getOrDefault("endpoint", "");
        accessKey = properties.getOrDefault("access_key", "");
        secretKey = properties.getOrDefault("secret_key", "");

        if (endpoint.equals("") || accessKey.equals("") || secretKey.equals("")) {
            String propertyPath = properties.getOrDefault("conf_file", "");
            if (propertyPath.equals("")) {
                throw new RuntimeException("No access details given");
            } else {
                try {
                    InputStream inputStream = new FileInputStream(propertyPath);

                    Properties prop = new Properties();
                    try {
                        prop.load(inputStream);
                    } catch (IOException ioe) {
                        throw new RuntimeException(ioe);
                    }

                    endpoint = prop.getProperty("endpoint");
                    accessKey = prop.getProperty("access_key");
                    secretKey = prop.getProperty("secret_key");

                } catch (FileNotFoundException fnfe) {
                    throw new RuntimeException("Configuration file not found");
                }
            }
        }

        try {
            minio = new MinioClient(endpoint, accessKey, secretKey);
        } catch (InvalidEndpointException | InvalidPortException iee) {
            _log.error("Exception creating minio client: " + iee);
            throw new RuntimeException("Unable to create Minio client");
        } catch (Exception e) {
            _log.error("Unknown error: " + e);
            throw new RuntimeException("Unable to create Minio client");
        }
    }

    /**
     * Cancels all requests and initiates a shutdown of the nearline storage
     * interface.
     * <p>
     * This method does not wait for actively executing requests to
     * terminate.
     */
    @Override
    public void shutdown()
    {
        _log.debug("Shutdown triggered");
        executor.shutdown();
    }
}
