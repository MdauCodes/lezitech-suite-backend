package com.lezitech.suite.files;

/**
 * Where uploaded bytes live. Live: Cloudflare R2 ({@link R2FileStore}); local runs and tests: a folder
 * ({@link DiskFileStore}). Chosen by {@code suite.files.store} (FILES_STORE = r2 | disk).
 */
public interface FileStore {

    void put(String key, byte[] bytes, String contentType);

    /** The bytes, or null when the key isn't there. */
    byte[] get(String key);

    void delete(String key);

    String name();
}
