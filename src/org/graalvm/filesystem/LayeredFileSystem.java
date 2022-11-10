package org.graalvm.filesystem;

import org.graalvm.collections.Pair;
import org.graalvm.polyglot.io.FileSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class LayeredFileSystem implements FileSystem {

    private final FileSystem baseLayer;
    private final Map<Path, FileSystem> overlay;

    public LayeredFileSystem(FileSystem baseLayer, Map<Path, FileSystem> overlay) {
        this.baseLayer = baseLayer;
        this.overlay = overlay;
    }

    @Override
    public Path parsePath(URI uri) {
        return createLayeredPath(baseLayer.parsePath(uri));
    }

    @Override
    public Path parsePath(String path) {
        return createLayeredPath(baseLayer.parsePath(path));
    }

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions) throws IOException {
        LayeredPath lp = LayeredPath.as(path);
        baseLayer.checkAccess(lp.base, modes, linkOptions);
        if (lp.overlay != null) {
            overlayFsFor(lp).checkAccess(lp.overlay, modes, linkOptions);
        }
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        LayeredPath lp = LayeredPath.as(dir);
        if (lp.overlay != null) {
            overlayFsFor(lp).createDirectory(lp.overlay, attrs);
        } else {
            baseLayer.createDirectory(lp.base, attrs);
        }
    }

    @Override
    public void delete(Path path) throws IOException {
        LayeredPath lp = LayeredPath.as(path);
        if (lp.overlay != null) {
            overlayFsFor(lp).delete(lp.overlay);
        } else {
            baseLayer.delete(lp.base);
        }
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        LayeredPath lp = LayeredPath.as(path);
        if (lp.overlay != null) {
            return overlayFsFor(lp).newByteChannel(lp.overlay, options, attrs);
        } else {
            return baseLayer.newByteChannel(lp.base, options, attrs);
        }
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        LayeredPath lp = LayeredPath.as(dir);
        boolean inOverlay = lp.overlay != null || overlayFsFor(lp) != null;
        DirectoryStream<Path> delegate;
        if (inOverlay) {
            delegate = overlayFsFor(lp).newDirectoryStream(lp.overlay != null ? lp.overlay : overlayFsFor(lp).parsePath("/") , filter);
        } else {
            delegate = baseLayer.newDirectoryStream(lp.base, filter);
        }
        return new LayeredDirectoryStream(lp, delegate, inOverlay);
    }

    @Override
    public Path toAbsolutePath(Path path) {
        if (path.isAbsolute()) {
            return path;
        }
        LayeredPath lp = LayeredPath.as(path);
        assert lp.overlay == null;
        return createLayeredPath(lp.base.toAbsolutePath());
    }

    @Override
    public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
        LayeredPath lp = LayeredPath.as(path);
        assert path.isAbsolute() || lp.overlay == null;
        return createLayeredPath(lp.base.toRealPath(linkOptions));
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        LayeredPath lp = LayeredPath.as(path);
        if (lp.overlay != null) {
            return overlayFsFor(lp).readAttributes(lp.overlay, attributes, options);
        } else {
            return baseLayer.readAttributes(lp.base, attributes, options);
        }
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        LayeredPath lp = LayeredPath.as(path);
        if (lp.overlay != null) {
            overlayFsFor(lp).setAttribute(lp.overlay, attribute, value, options);
        } else {
            baseLayer.setAttribute(lp.base, attribute, value, options);
        }
    }

    @Override
    public void createLink(Path link, Path existing) throws IOException {
        LayeredPath linkLp = LayeredPath.as(link);
        LayeredPath existingLp = LayeredPath.as(existing);
        if (linkLp.overlay != null || existingLp.overlay != null) {
            throw new IllegalArgumentException();
        }
        baseLayer.createLink(linkLp.base, existingLp.base);
    }

    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
        LayeredPath linkLp = LayeredPath.as(link);
        LayeredPath targetLp = LayeredPath.as(target);
        if (linkLp.overlay != null || targetLp.overlay != null) {
            throw new IllegalArgumentException();
        }
        baseLayer.createSymbolicLink(linkLp.base, targetLp.base);
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        LayeredPath linkLp = LayeredPath.as(link);
        if (linkLp.overlay != null) {
            throw new IllegalArgumentException();
        }
        return new LayeredPath(baseLayer.readSymbolicLink(linkLp.base), null);
    }

    @Override
    public void setCurrentWorkingDirectory(Path currentWorkingDirectory) {
        LayeredPath lp = LayeredPath.as(currentWorkingDirectory);
        if (lp.overlay != null) {
            throw new UnsupportedOperationException("Current working directory must be on base fs.");
        }
        baseLayer.setCurrentWorkingDirectory(lp.base);
    }

    @Override
    public String getSeparator() {
        return baseLayer.getSeparator();
    }

    @Override
    public String getPathSeparator() {
        return baseLayer.getPathSeparator();
    }

    @Override
    public String getMimeType(Path path) {
        return null;
    }

    @Override
    public Charset getEncoding(Path path) {
        return null;
    }

    @Override
    public Path getTempDirectory() {
        return baseLayer.getTempDirectory();
    }

    @Override
    public boolean isSameFile(Path path1, Path path2, LinkOption... options) throws IOException {
        return false;
    }

    private LayeredPath createLayeredPath(Path path) {
        Pair<Path,Path> paths = split(path);
        Path basePath = paths.getLeft();
        Path overlayPath;
        if (paths.getRight() != null) {
            overlayPath = overlay.get(paths.getLeft()).parsePath(paths.getRight().toString()).toAbsolutePath();
        } else {
            overlayPath = null;
        }
        return new LayeredPath(basePath, overlayPath);
    }

    private Pair<Path,Path> split(Path path) {
        for (Map.Entry<Path,FileSystem> e : overlay.entrySet()) {
            if (path.startsWith(e.getKey())) {
                return Pair.create(e.getKey(), e.getKey().relativize(path));
            }
        }
        return Pair.create(path, null);
    }

    private FileSystem overlayFsFor(LayeredPath path) {
        return overlay.get(path.base);
    }

    private static final class LayeredDirectoryStream implements DirectoryStream<Path> {

        private LayeredPath folder;
        private DirectoryStream<Path> delegate;
        private boolean inOverlay;

        LayeredDirectoryStream(LayeredPath folder, DirectoryStream<Path> delegate, boolean inOverlay) {
            this.folder = folder;
            this.delegate = delegate;
            this.inOverlay = inOverlay;
        }

        @Override
        public Iterator<Path> iterator() {
            Iterator<Path> delegateIt = delegate.iterator();
            return new Iterator<Path>() {
                @Override
                public boolean hasNext() {
                    return delegateIt.hasNext();
                }

                @Override
                public Path next() {
                    Path p = delegateIt.next();
                    Path base;
                    Path overlay;
                    if (inOverlay) {
                        base = folder.base;
                        overlay = p;
                    } else {
                        base = p;
                        overlay = null;
                    }
                    return new LayeredPath(base, overlay);
                }
            };
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class LayeredPath implements Path {

        private final Path base;
        private final Path overlay;

        LayeredPath(Path base, Path overlay) {
            this.base = base;
            this.overlay = overlay;
        }

        static LayeredPath as (Path path) {
            return (LayeredPath) path;
        }

        @Override
        public java.nio.file.FileSystem getFileSystem() {
            return null;
        }

        @Override
        public boolean isAbsolute() {
            return base.isAbsolute();
        }

        @Override
        public Path getRoot() {
            return new LayeredPath(base.getRoot(), null);
        }

        @Override
        public Path getFileName() {
            if (overlay != null) {
                return new LayeredPath(adoptToBase(overlay.getFileName()), null);
            } else {
                return new LayeredPath(base.getFileName(), null);
            }
        }

        @Override
        public Path getParent() {
            Path baseParent = base;
            Path overlayParent;
            if (overlay != null) {
                overlayParent = overlay.getParent();
                if (overlayParent == null || overlayParent.getRoot() == overlayParent) {
                    overlayParent = null;
                }
            } else {
                baseParent = base.getParent();
                overlayParent = null;
            }
            return new LayeredPath(baseParent, overlayParent);
        }

        @Override
        public int getNameCount() {
            int count = base.getNameCount();
            if (overlay != null) {
                count += overlay.getNameCount() - 1; // do not count root
            }
            return count;
        }

        @Override
        public Path getName(int i) {
            if (i < base.getNameCount()) {
                return new LayeredPath(base.getName(i), null);
            } else if (overlay != null) {
                return new LayeredPath (adoptToBase(overlay.getName(i)), null);
            } else {
                throw new IndexOutOfBoundsException("Index: " + i + ", name count: " + base.getNameCount());
            }
        }

        @Override
        public Path subpath(int s, int e) {
            if (s < 0 || e > getNameCount()) {
                throw new IllegalArgumentException();
            }
            if (e < base.getNameCount()) {
                return new LayeredPath(base.subpath(s,e), null);
            } else if (s >= base.getNameCount()) {
                return new LayeredPath(adoptToBase(overlay.subpath(s - base.getNameCount(), e - base.getNameCount())),null);
            } else {
                Path p1 = base.subpath(s, getNameCount());
                Path p2 = overlay.subpath(0, e - base.getNameCount());
                return new LayeredPath(p1.resolve(p2), null);
            }

        }

        @Override
        public boolean startsWith(Path path) {
            LayeredPath otherPath = (LayeredPath) path;
            if (!base.startsWith(otherPath.base)) {
                return false;
            } else if (otherPath.overlay == null) {
                return true;
            } else if (overlay == null) {
                return false;
            } else {
                return overlay.startsWith(otherPath.overlay);
            }
        }

        @Override
        public boolean endsWith(Path path) {
            if (path.isAbsolute()) {
                return this.equals(path);
            }
            if (getNameCount() < path.getNameCount()) {
                return false;
            }
            for (int thisIndex = getNameCount() - 1, otherIndex = path.getNameCount() - 1; otherIndex >= 0;) {
                if (!getName(thisIndex--).equals(path.getName(otherIndex--))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Path normalize() {
            return new LayeredPath(base.normalize(), overlay == null ? null : overlay.normalize());
        }

        @Override
        public Path resolve(Path path) {
            LayeredPath toResolve = (LayeredPath) path;
            if (toResolve.base.isAbsolute()) {
                return toResolve;
            }
            if (toResolve.overlay != null) {
                throw new IllegalStateException("Relative path with overlay should not exist");
            }
            if (overlay == null) {
                return new LayeredPath(base.resolve(toResolve.base), null);
            } else {
                return new LayeredPath(base, overlay.resolve(adoptToOverlay(toResolve.base)));
            }
        }

        @Override
        public Path relativize(Path path) {
            LayeredPath otherPath = (LayeredPath) path;
            Path relativizedBase = base.relativize(otherPath.base);
            if (overlay == null && otherPath.overlay == null) {
                return new LayeredPath(base.relativize(otherPath.base), null);
            } else if (overlay == null) {
                if (relativizedBase.isAbsolute()) {
                    return new LayeredPath(relativizedBase, null);
                } else {
                    return new LayeredPath(relativizedBase.resolve(overlay.toString()), null);
                }
            } else if (otherPath.overlay != null && base.equals(otherPath.base)) {
                return new LayeredPath(adoptToBase(overlay.relativize(otherPath.overlay)), null);
            } else {
                throw new UnsupportedOperationException();
            }
        }

        @Override
        public URI toUri() {
            URI uri = base.toUri();
            if (overlay != null) {
                uri = URI.create("zip:" + uri.toString() + "!/" + overlay.toAbsolutePath().toString());
            }
            return uri;
        }

        @Override
        public Path toAbsolutePath() {
            return new LayeredPath(base.toAbsolutePath(), overlay == null ? overlay : overlay.toAbsolutePath());
        }

        @Override
        public Path toRealPath(LinkOption... linkOptions) throws IOException {
            return new LayeredPath(base.toRealPath(linkOptions), overlay == null ? overlay : overlay.toRealPath(linkOptions));
        }

        @Override
        public WatchKey register(WatchService watchService, WatchEvent.Kind<?>[] kinds, WatchEvent.Modifier... modifiers) throws IOException {
            return null;
        }

        @Override
        public int compareTo(Path path) {
            LayeredPath other = (LayeredPath) path;
            int res = base.compareTo(other.base);
            if (res != 0) {
                return res;
            }
            if (overlay != null) {
                return other.overlay != null ? overlay.compareTo(other.overlay) : 1;
            } else {
                return other.overlay != null ? -1 : 0;
            }
        }

        @Override
        public String toString() {
            String res = base.toString();
            if (overlay != null) {
                res = res + overlay.toString();
            }
            return res;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LayeredPath paths = (LayeredPath) o;
            return Objects.equals(base, paths.base) && Objects.equals(overlay, paths.overlay);
        }

        @Override
        public int hashCode() {
            return Objects.hash(base, overlay);
        }

        private Path adoptToOverlay(Path path) {
            return overlay.getRoot().relativize(overlay.getRoot().resolve(path.toString()));
        }

        private Path adoptToBase(Path path) {
            return base.getRoot().relativize(base.getRoot().resolve(path.toString()));
        }
    }
}
