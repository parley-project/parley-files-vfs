# parley-files-vfs

> **In development** (Parley 2.0.0, To-Do 17.1). The shared base and the first scheme, `vfssftp`, are built and
> tested; the other schemes come next. [docs/DESIGN.md](docs/DESIGN.md) maps out how it is built.

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

| Type id (VFS scheme) | Add | VFS can write |
|---|---|---|
| `vfssftp` (`sftp`) | `com.github.mwiede:jsch` (the maintained JSch fork, same packages as `com.jcraft:jsch`) | yes (random access: read only) |
| `vfsftp`, `vfsftps` (`ftp`, `ftps`) | `commons-net:commons-net` | yes (random access: read only) |
| `vfszip`, `vfsjar` (`zip`, `jar`) | nothing | no (read only) |
| `vfstar`, `vfstgz`, `vfstbz2` (`tar`, `tgz`, `tbz2`) | `org.apache.commons:commons-compress` | no (read only) |
| `vfshttp`, `vfshttps` (`http`, `https`) | `org.apache.httpcomponents.client5:httpclient5` | no (read only, no listing) |
| `vfswebdav4`, `vfswebdav4s` (`webdav4`, `webdav4s`) | `org.apache.commons:commons-vfs2-jackrabbit2` (VFS's WebDAV provider is its own artifact) | yes (random access: read only) |

Each type id is `vfs` + the VFS scheme, and is also the scheme of its URLs: `vfszip:file:///tmp/a.zip!/dir` is VFS's
`zip:file:///tmp/a.zip!/dir`, and `FileSourceFactory.getFileSource("vfszip:...")` opens it. The prefix keeps them
apart from Parley's own `sftp`, `ftp` and `ftps` and from the URL schemes the JDK owns (`http`, `https`, `jar`, `file`).

## Using vfssftp

`vfssftp` takes parley-files-sftp's settings, with the same names and defaults (`host`, `port` 22, `user`, `auth`,
`password`, `identityFile`, `privateKey`, `knownHosts`, `strictHostKeyChecking` yes, `connectTimeout`), so a saved
sftp connection works as a vfssftp one. Add `com.github.mwiede:jsch` to the class path.

```java
FileSourceFactory f = FileSourceFactory.getFileSourceFactory("vfssftp");
f.connect(settings);
FileSource file = f.createFileSource("/home/tony/a.txt");
// or by URL, with no factory in hand:
FileSource same = FileSourceFactory.getFileSource("vfssftp://tony@host/home/tony/a.txt");
```

What it can't do, from VFS: write at a position in a file (`getRandomAccessStream("rw")` is refused; `"r"` works),
set the group's or other users' permission bits alone (those setters return false; the owner's, or everyone's at
once, can be set), make links, or say who owns a file. `createNewFile()` and `mkdir()` are **not atomic**
(`getCapabilities()` says so). Logging in through an ssh-agent isn't supported yet.

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
   `java.net.URL` needs a handler for its scheme, which can't be given for one the JDK already owns (http, https, jar,
   file) or for Parley's own sftp, ftp and ftps. Hence type ids and URL schemes named `vfs` + the scheme
   (`vfszip:file:///tmp/a.zip!/dir`), which `FileSourceFactory.getFileSource(URL)` opens.
8. **VFS's sftp runs `id -u` and `id -G` on the server** (an exec channel) to work out what the user may do. On a
   server that allows SFTP only, it can't, and takes the user to be every file's owner.
9. **VFS has no owner, group or other permission bits in its API**, only what the connected user can do. sftp and ftp
   keep the full mode inside VFS, where only reflection reaches it.
10. **A JSch stream read by a thread that has since ended is dropped.** JSch's pipe remembers the last thread that
    read it; when more data arrives after that thread has ended, JSch closes the channel, and closing the stream then
    fails ("Pipe closed"). Reading a file in one thread and closing it in another, after the first has ended (a
    thread pool's thread, say), runs into it. A finding for the 17.2 robustness run.
11. **parley-files' `FileSourceURLConnection` takes only `filesource:` URLs**, so a `vfs<scheme>:` URL's
    `openConnection()` uses this module's `VfsUrlConnection`, which opens the file with `getFileSource(URL)` and
    disconnects that factory when its stream is closed. (`FileSourceFactory.getFileSource(URL)` itself needed no
    change.)
