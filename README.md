# parley-files-vfs

> **In development** (Parley 2.0.0, To-Do 17.1). Nothing is implemented yet; [docs/DESIGN.md](docs/DESIGN.md) maps out
> how it will be built.

[parley-files](https://github.com/parley-project/parley-files) `FileSource` implementations for the file systems
[Apache Commons VFS](https://commons.apache.org/proper/commons-vfs/) reaches, part of **Parley**, a family of Java
libraries for implementing internet protocols. It lets a `FileSource` open VFS schemes Parley doesn't cover itself:
zip, jar and tar archives, HTTP and HTTPS, WebDAV, HDFS.

Put it on the class path and `FileSourceFactory` finds its factories through `ServiceLoader`, one per VFS scheme;
application code keeps working with `FileSource` and doesn't need to name this module.

Requires Java 11 or later. Depends on `parley-files` and `commons-vfs2` (which brings `commons-io`,
`commons-lang3` and `commons-logging`).

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-files-vfs</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

## Each scheme's own library

VFS needs a library of its own for most schemes, and finds a scheme only when that library is on the class path. This
module doesn't depend on them: add the one for each scheme you use.

| Scheme | Add | VFS can write |
|---|---|---|
| `vfs-sftp` | `com.github.mwiede:jsch` (the maintained JSch fork, same packages as `com.jcraft:jsch`) | yes (random access: read only) |
| `vfs-ftp`, `vfs-ftps` | `commons-net:commons-net` | yes (random access: read only) |
| `zip`, `jar` | nothing | no (read only) |
| `tar`, `tgz`, `tbz2` | `org.apache.commons:commons-compress` | no (read only) |
| `http`, `https` | `org.apache.httpcomponents.client5:httpclient5` | no (read only, no listing) |
| `webdav4`, `webdav4s` | `org.apache.commons:commons-vfs2-jackrabbit2` (VFS's WebDAV provider is its own artifact) | yes (random access: read only) |

The `vfs-` prefix on sftp, ftp and ftps keeps them apart from Parley's own `sftp`, `ftp` and `ftps` while the two are
compared (To-Do 17.2).
