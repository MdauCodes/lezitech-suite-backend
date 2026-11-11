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

/** Cloudflare R2 through its S3-compatible API. Keys come from env vars (R2_ACCOUNT_ID, R2_ACCESS_KEY, R2_SECRET_KEY, R2_BUCKET). */
public class R2FileStore implements FileStore {

    private final S3Client s3;
    private final String bucket;

    public R2FileStore(String accountId, String accessKey, String secretKey, String bucket) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create("https://" + accountId + ".r2.cloudflarestorage.com"))
                .region(Region.of("auto"))
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
        return "r2";
    }
}
