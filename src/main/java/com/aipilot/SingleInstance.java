package com.aipilot;

import com.aipilot.infra.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

/** Cross-process single-instance lock under the AIPilot app data directory. */
public final class SingleInstance {
    private static final Logger log = LoggerFactory.getLogger(SingleInstance.class);

    private static RandomAccessFile lockFile;
    private static FileChannel channel;
    private static FileLock lock;

    private SingleInstance() {
    }

    /** @return true if this process owns the instance lock */
    public static boolean tryAcquire(AppPaths paths) {
        try {
            Path dir = paths == null ? AppPaths.resolve().appDir() : paths.appDir();
            Files.createDirectories(dir);
            Path file = dir.resolve("aipilot.lock");
            lockFile = new RandomAccessFile(file.toFile(), "rw");
            channel = lockFile.getChannel();
            lock = channel.tryLock();
            if (lock == null) {
                closeQuietly();
                return false;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(SingleInstance::release, "aipilot-unlock"));
            return true;
        } catch (Exception e) {
            log.warn("Single-instance lock failed: {}", e.getMessage());
            closeQuietly();
            // Do not brick startup on unexpected FS errors.
            return true;
        }
    }

    public static void release() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly();
        }
    }

    private static void closeQuietly() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (lockFile != null) {
                lockFile.close();
            }
        } catch (Exception ignored) {
        }
        lock = null;
        channel = null;
        lockFile = null;
    }
}
