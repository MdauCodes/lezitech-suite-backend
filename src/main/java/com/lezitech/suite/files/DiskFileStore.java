package com.lezitech.suite.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Files in a local folder: for development and tests, never for live businesses. */
public class DiskFileStore implements FileStore {

    private final Path root;

    public DiskFileStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    private Path path(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Bad file key");
        }
        return p;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        try {
            Path p = path(key);
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            Path p = path(key);
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String name() {
        return "disk";
    }
}
