package com.lezitech.suite.files;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.net.URI;

/**
 * An S3-compatible bucket: Cloudflare R2 (R2_ACCOUNT_ID, R2_ACCESS_KEY, R2_SECRET_KEY, R2_BUCKET) or
 * Backblaze B2 (B2_ENDPOINT, B2_REGION, B2_KEY_ID, B2_APP_KEY, B2_BUCKET). Same keys, same files, so moving
 * from one to the other is copying the bucket and changing FILES_STORE.
 */
public class R2FileStore implements FileStore {

    private final S3Client s3;
    private final String bucket;
    private final String name;

    /** Cloudflare R2. */
    public R2FileStore(String accountId, String accessKey, String secretKey, String bucket) {
        this("https://" + accountId + ".r2.cloudflarestorage.com", "auto", accessKey, secretKey, bucket, "r2");
    }

    /** Any S3-compatible store, e.g. Backblaze B2 at https://s3.eu-central-003.backblazeb2.com, region eu-central-003. */
    public R2FileStore(String endpoint, String region, String accessKey, String secretKey, String bucket, String name) {
        this.bucket = bucket;
        this.name = name;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }

    @Override
    public String name() {
        return name;
    }
}
