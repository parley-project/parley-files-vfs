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
compared (To-Do 17.2). All the type ids may become `vfs` + the scheme (`vfszip`, `vfssftp` ...), to match the URL
handlers ([docs/DESIGN.md](docs/DESIGN.md), Q9).

## What VFS does that matters here

Found by checking Commons VFS 2.10.0's code and its capabilities at run time (2026-11-13). Each one shapes the design
([docs/DESIGN.md](docs/DESIGN.md)):

1. **VFS can't write its archive formats.** zip, jar, tar, tgz and tbz2 are read only, so "read and write" (To-Do 17.1)
   holds only for sftp, ftp, ftps and WebDAV. (gz and bz2 can write their one file.)
2. **No remote scheme can write at a position in a file**: random access is read only for sftp, ftp, ftps, WebDAV and
   http. **http can't list a folder.**
3. **Moving a file onto one that exists deletes the one that exists** (VFS's `moveTo` calls `deleteAll()` on it, so a
   folder goes with everything in it) before renaming; across file systems it copies, then deletes. `renameTo` checks
   for itself first.
4. **VFS's create-folder is mkdirs**: it creates missing parents and quietly does nothing if the folder exists. Its
   create-file also creates parents and does nothing if the file exists. So `mkdir()` and `createNewFile()` check for
   themselves, and neither is atomic.
5. **VFS's sftp takes `/` to be the user's home directory** unless told otherwise (`userDirIsRoot` is true by default).
   It is set false here, so paths are the server's, as in parley-files-sftp.
6. **Each factory has a VFS manager of its own.** VFS's shared one (`VFS.getManager()`) serves the whole JVM, and
   closing it would close everyone's connections.
7. **A plain VFS URL can't be opened through `FileSource.of(URI)`** (it takes only `filesource:` and `file:`), and a
   `java.net.URL` can't be had for a scheme with a `-` in it or for one the JDK already owns (http, https, jar, file). Hence the URL
   handlers named `vfs` + the scheme (`vfszip:file:///tmp/a.zip!/dir`).
8. **VFS's sftp runs `id -u` and `id -G` on the server** (an exec channel) to work out what the user may do. On a
   server that allows SFTP only, it can't, and takes the user to be every file's owner.
9. **VFS has no owner, group or other permission bits in its API**, only what the connected user can do. sftp and ftp
   keep the full mode inside VFS, where only reflection reaches it.
