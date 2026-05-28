/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.store;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.opensearch.common.lucene.store.FilterIndexOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A thin directory wrapper that extends a standard Lucene FSDirectory to handle
 * sidecar files stored in subdirectories of the index directory. Sidecar files
 * are referenced with relative paths like {@code _sidecar_embedding_12345/_0.dvd}.
 *
 * <p>This wrapper intercepts calls for files that contain a path separator and
 * resolves them relative to the index directory, enabling segment replication to
 * read sidecar files from their subdirectories without requiring the full
 * {@link SubdirectoryAwareDirectory} infrastructure.</p>
 *
 * @opensearch.internal
 */
public class SidecarAwareDirectory extends FilterDirectory {

    private final Path indexDir;

    /**
     * Wraps the given directory with sidecar-file awareness.
     *
     * @param delegate the underlying directory (typically FSDirectory for the index)
     * @param indexDir the path to the index directory where sidecar subdirectories reside
     */
    public SidecarAwareDirectory(Directory delegate, Path indexDir) {
        super(delegate);
        this.indexDir = indexDir;
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        if (isSidecarFile(name)) {
            // Sidecar files live in subdirectories - open via FSDirectory for that subdirectory
            Path filePath = indexDir.resolve(name);
            Path parentDir = filePath.getParent();
            String fileName = filePath.getFileName().toString();
            try (Directory subDir = FSDirectory.open(parentDir)) {
                // We need to keep the IndexInput alive after closing subDir,
                // so we open it and return - the input holds its own file handle
                return subDir.openInput(fileName, context);
            }
        }
        return super.openInput(name, context);
    }

    @Override
    public long fileLength(String name) throws IOException {
        if (isSidecarFile(name)) {
            Path filePath = indexDir.resolve(name);
            return Files.size(filePath);
        }
        return super.fileLength(name);
    }

    @Override
    public IndexOutput createOutput(String name, IOContext context) throws IOException {
        if (isSidecarFile(name)) {
            Path filePath = indexDir.resolve(name);
            Files.createDirectories(filePath.getParent());
            Path parentDir = filePath.getParent();
            String fileName = filePath.getFileName().toString();
            Directory subDir = FSDirectory.open(parentDir);
            IndexOutput output = subDir.createOutput(fileName, context);
            // Return a delegating output that closes the directory when the output is closed
            return new FilterIndexOutput("sidecar:" + name, output) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        subDir.close();
                    }
                }
            };
        }
        return super.createOutput(name, context);
    }

    @Override
    public String[] listAll() throws IOException {
        String[] baseFiles = super.listAll();
        // Also list files in sidecar subdirectories
        Set<String> allFiles = new HashSet<>(Arrays.asList(baseFiles));
        try (var dirStream = Files.newDirectoryStream(indexDir, "_sidecar_*")) {
            for (Path subDir : dirStream) {
                if (Files.isDirectory(subDir)) {
                    String subDirName = subDir.getFileName().toString();
                    try (var fileStream = Files.newDirectoryStream(subDir)) {
                        for (Path file : fileStream) {
                            if (Files.isRegularFile(file)) {
                                allFiles.add(subDirName + "/" + file.getFileName().toString());
                            }
                        }
                    }
                }
            }
        } catch (java.nio.file.NoSuchFileException e) {
            // Index directory may not exist yet during initialization
        }
        return allFiles.stream().sorted().toArray(String[]::new);
    }

    @Override
    public void deleteFile(String name) throws IOException {
        if (isSidecarFile(name)) {
            Path filePath = indexDir.resolve(name);
            Files.deleteIfExists(filePath);
            return;
        }
        super.deleteFile(name);
    }

    @Override
    public void sync(Collection<String> names) throws IOException {
        // Separate sidecar files from regular files
        Set<String> regularFiles = names.stream().filter(n -> !isSidecarFile(n)).collect(Collectors.toSet());
        if (!regularFiles.isEmpty()) {
            super.sync(regularFiles);
        }
        // Sidecar files are fsynced via their own writer; no additional sync needed
    }

    /**
     * Returns whether the given file name represents a sidecar file (contains a path separator).
     */
    private static boolean isSidecarFile(String name) {
        return name.contains("/");
    }
}
