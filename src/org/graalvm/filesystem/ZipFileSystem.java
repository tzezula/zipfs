package org.graalvm.filesystem;

import org.graalvm.polyglot.io.FileSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.util.Map;
import java.util.Set;

public class ZipFileSystem implements FileSystem {

    private final java.nio.file.FileSystem zipFs;
    private final Path root;

    public ZipFileSystem(Path pathToArchive) throws IOException {
        zipFs = FileSystems.newFileSystem(pathToArchive, null);
        this.root = zipFs.getRootDirectories().iterator().next();
    }

    @Override
    public Path parsePath(URI uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Path parsePath(String path) {
        return zipFs.getPath(path);
    }

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions) throws IOException {
        zipFs.provider().checkAccess(path, modes.toArray(new AccessMode[modes.size()]));
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        zipFs.provider().createDirectory(dir, attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        zipFs.provider().delete(path);
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        return zipFs.provider().newByteChannel(path, options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        return zipFs.provider().newDirectoryStream(dir, filter);
    }

    @Override
    public Path toAbsolutePath(Path path) {
        return path.toAbsolutePath();
    }

    @Override
    public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
        return toAbsolutePath(path);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return zipFs.provider().readAttributes(path, attributes, options);
    }
}
