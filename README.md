# parley-files-vfs

> **In development** (Parley 2.0.0, To-Do 17.1). The shared base and the schemes `vfssftp`, `vfsftp` and `vfsftps`
> are built and tested; the other schemes come next. [docs/DESIGN.md](docs/DESIGN.md) maps out how it is built.

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

## Using vfsftp and vfsftps

`vfsftp` and `vfsftps` take parley-files-ftp's settings, with the same names and defaults (`host`, `port` 21 or 990,
`user`, `password`, `secure` false, `timeout` 4s), so a saved ftp connection works as a vfsftp one. As in
parley-files-ftp, `vfsftp` with `secure` true is explicit TLS (AUTH TLS) and `vfsftps` is implicit TLS. Add
`commons-net:commons-net` to the class path.

```java
FileSource file = FileSourceFactory.getFileSource("vfsftp://ftp@host/pub/a.txt");
// explicit TLS, and implicit TLS:
FileSource tls = FileSourceFactory.getFileSource("vfsftp://tony@host/home/tony/a.txt?secure=true");
FileSource ftps = FileSourceFactory.getFileSource("vfsftps://tony@host/home/tony/a.txt");
```

With TLS the server's certificate is checked as Parley's FTP client checks it: trusted by the application's trust
managers (`SecureBaseObject.getDefaultTrustManagers()`) or the JVM's, and for the host connected to. What they can't
do, from VFS: write at a position, set permission bits or times, send ACCT (parley-files-ftp's `account`), or make
links. A directory's time is the listing's (often to the minute); a file's is to the second where the server has MDTM.

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
12. **VFS's ftp uses active mode and takes `/` to be the login directory** unless told otherwise. vfsftp sets passive
    mode and the server's root, as parley-files-ftp has them.
13. **VFS's ftps sends no PROT unless told**, so after a TLS login the files and listings go in the clear. vfsftp and
    vfsftps send PBSZ 0 and PROT P.
14. **VFS's ftps accepts any certificate that is in date**, self-signed or not, for any host: commons-net's default
    trust manager checks only the dates, and VFS can't turn on commons-net's host name check. vfsftp and vfsftps check
    the chain and the host as Parley's FTP client does (`VfsTrustManager`).
15. **VFS's ftp asks MDTM for a directory's time when MDTM is on, and fails** (servers answer MDTM for files). Without
    MDTM it uses the listing's time, to the minute at best. vfsftp uses MDTM for files and the listing for directories.
16. **VFS's ftp takes a transfer the client ended early (426) as a failed close** unless told which replies are fine,
    so reading only the start of a file throws. vfsftp gives it VFS's own list (426, 550).
17. **VFS's ftp keeps the server's listing entry, and its permission bits, in a private field** (`ftpFile`), and can't
    set a permission bit or a time. vfsftp reads the bits by reflection.
18. **VFS's sftp changes a permission by sending the whole stat back** (size, owner, group, times, mode), not a chmod.
    A server can refuse a part of it (Apache MINA refuses the times on a file its owner can't read), and a file that
    grows between VFS's stat and its setstat could be cut back (docs/DESIGN.md, 10.5).
