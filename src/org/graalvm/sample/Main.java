package org.graalvm.sample;


import org.graalvm.filesystem.LayeredFileSystem;
import org.graalvm.filesystem.ZipFileSystem;
import org.graalvm.polyglot.io.FileSystem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;

public class Main {


    public static void main(String[] args) throws IOException  {
        FileSystem defaultFs = FileSystem.newDefaultFileSystem();
        Path mountPoint = defaultFs.parsePath("/Users/tom/Downloads/files/");
        FileSystem zfs = new ZipFileSystem(Paths.get("/Users/tom/Downloads/content.zip"));
        LayeredFileSystem lfs = new LayeredFileSystem(defaultFs,
                Collections.singletonMap(mountPoint, zfs));

        Path p = lfs.parsePath("/Users/tom/Downloads/files");
        lfs.checkAccess(p, Collections.singleton(AccessMode.READ));
        try (DirectoryStream<Path> ds = lfs.newDirectoryStream(p, (a) -> true)) {
            for (Path lp : ds) {
                boolean file = (boolean) lfs.readAttributes(lp, "basic:isRegularFile").get("isRegularFile");
                System.out.println(lp + " " + file);
                if (file) {
                    try (SeekableByteChannel c = lfs.newByteChannel(lp, Collections.singleton(StandardOpenOption.READ))) {
                        ByteBuffer b = ByteBuffer.allocate(1024);
                        c.read(b);
                        b.flip();
                        CharBuffer content = StandardCharsets.US_ASCII.decode(b);
                        System.out.println("<Content>");
                        System.out.println(new String(content.array(), 0, content.limit()));
                        System.out.println("</Content>");
                    }
                }
            }
        }
    }
}
