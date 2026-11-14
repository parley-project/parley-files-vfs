# parley-files-vfs: how FileSourceFactory and FileSource map onto Commons VFS

As of 2026-11-13, against Apache Commons VFS 2.10.0 (the current release, Java 8+) and parley-files 2.0.0-SNAPSHOT
(release-2.0.0). Sections 1 and 2 are the decisions already in the Parley 2.0.0 To-Do doc (17.1, 17.2) and what VFS
itself does; sections 3 to 7 are the design; section 8 records the decisions made on it (2026-11-13) and what is still
open; section 9 records what was built (the shared base and `vfssftp`, 2026-11-13) and where it differs from the design;
section 10 records `vfsftp` and `vfsftps` (2026-11-13), the next schemes, which To-Do 17.2 needs.

## 1. Decisions already made (To-Do 17.1 and 17.2, 2026-11-07)

1. Read and write.
2. One factory per VFS scheme, because the registry maps one type id to one class. Its type id was to be the scheme
   (`zip`, `tar` ...); changed 2026-11-13 (Q9) to `vfs` + the scheme (`vfszip`, `vfstar`, `vfshttp` ...). Almost all the code is shared; each scheme's factory is a small subclass.
3. Files are addressed by the plain VFS URL, e.g. `zip:file:///tmp/a.zip!/dir`.
4. One direct dependency, `commons-vfs2`. A scheme's own library (JSch, commons-compress, httpclient5 ...) is optional
   and added by whoever uses that scheme.
5. What a scheme can do (write, random access ...) is read from VFS's capabilities at run time, not coded per scheme.
   Once one scheme works, a further scheme is little more than configuration: its type id and connection settings.
6. Lock scope `PROCESS`; capabilities conservative (no atomic or durable guarantees claimed).
7. VFS schemes Parley already has (`sftp`, `ftp`, `ftps`) would clash in the registry. For the 17.2 comparison VFS's
   were to be registered as `vfs-sftp`, `vfs-ftp` and `vfs-ftps`; since Q9 (2026-11-13) they are `vfssftp`, `vfsftp`
   and `vfsftps`, which never clash, whatever the comparison finds.
8. Build order: the shared base with `vfssftp` first, then the other schemes.

## 2. What VFS gives us (checked against 2.10.0)

### 2.1 Capabilities per scheme

Read from `FileSystemManager.getProviderCapabilities(scheme)` with each scheme's library on the class path:

| Scheme | Write | Create / delete / rename | Append | Random access | Set modified time | List |
|---|---|---|---|---|---|---|
| `sftp` | yes | yes | yes | read only | files only | yes |
| `ftp`, `ftps` | yes | yes | yes | read only | no | yes |
| `webdav4`, `webdav4s` | yes | yes | no | read only | no | yes |
| `http`, `https` | no | no | no | read only | no | **no** |
| `zip`, `jar`, `tar`, `tgz`, `tbz2` | **no** | no | no | no | no | yes |
| `gz`, `bz2` (one file) | yes | no | no | no | no | yes |
| `ram`, `tmp`, `file` | yes | yes | yes | read and write | yes | yes |

Consequences:

1. **VFS's archives are read only.** Decision 1.1 (read and write) holds only for the schemes VFS can write: sftp, ftp,
   ftps and WebDAV. Writing a zip or tar would mean writing our own (commons-compress) layer; not proposed for 2.0.0.
2. No scheme gives random-access *write* except the local and in-memory ones Parley already has, so
   `getRandomAccessStream("rw")` is a read-only stream (or refused) everywhere that matters.
3. `http` can't list, so a `FileSource` over it can read a URL it is given but can't browse.
4. WebDAV is a second artifact, `commons-vfs2-jackrabbit2` (scheme `webdav4`; the older `webdav` needs Jackrabbit 1
   and is not worth supporting).

### 2.2 VFS behaviours that break the FileSource contract if passed straight through

1. **`moveTo` deletes an existing destination** (`destFile.deleteAll()`, recursively, before renaming), and across
   file systems it falls back to copy then delete. `renameTo(dest)` must check first (section 5.4).
2. `createFolder()` creates every missing ancestor and returns quietly if the folder exists, so it is `mkdirs`, not
   `mkdir`.
3. `createFile()` creates missing ancestors and does nothing if the file exists, so `createNewFile()` must check
   existence itself; neither is atomic.
4. sftp's `userDirIsRoot` defaults to **true**: `/` means the user's home directory. It must be set false so paths are
   the server's, as they are in parley-files-sftp (the 17.2 comparison needs the same paths).
5. `VFS.getManager()` is one manager shared by the whole JVM. Closing it closes every caller's file systems, against
   the rule that library code never changes the state of something it didn't create.
6. VFS caches `FileObject`s and their attributes (default `CacheStrategy.ON_RESOLVE`, `SoftRefFilesCache`).
7. VFS has no owner, group or other permission bits; only "can the connected user read / write / execute", and
   `setReadable(b, ownerOnly)` and the like.
8. VFS can tell that a file is a symbolic link (`isSymbolicLink()`) but not where it leads, and can't make one.
9. sftp's options take `java.io.File` for known hosts and identities (`setKnownHosts(File)`, `setIdentities(File...)`).
   Identities can be given as bytes instead (`BytesIdentityInfo`); known hosts can't (section 4.4).

## 3. Classes

All in `us.bringardner.parley.files.vfs`.

| Class | Extends / implements | Role |
|---|---|---|
| `VfsFileSourceFactory` | `FileSourceFactory` (abstract) | All the shared code: its own VFS manager, options, the root `FileObject`, settings, connect / disconnect |
| `VfsFileSource` | `BaseObject`, `FileSource` | One path on the factory's file system; wraps a VFS `FileObject` |
| `VfsRandomAccessIoController` | `AbstractRandomAccessIoController` | Random access over VFS's `RandomAccessContent`, where the scheme has it |
| `VfsSeekableInputStream` | `ISeekableInputStream` | Over `RandomAccessContent` in `READ` mode |
| `VfsAuthenticator` | VFS `UserAuthenticator` | Hands VFS the user and password when it connects, resolving a keychain reference then (section 4.3) |
| `VfsLog` | commons-logging `Log` | Sends VFS's own logging through the factory (BaseObject, then `System.Logger`), as parley-files-ftp's `FtpClientWrapper` does |
| `VfsSftpFileSourceFactory` | `VfsFileSourceFactory` | Type id `vfssftp`: the first scheme |
| `VfsFtpFileSourceFactory`, `VfsFtpsFileSourceFactory` | `VfsFileSourceFactory`, `VfsFtpFileSourceFactory` | Type ids `vfsftp` and `vfsftps` (section 10) |
| `VfsTrustManager` | `X509ExtendedTrustManager` | The server certificate check for VFS's ftps, as Parley's FTP client makes it (section 10) |
| later: `VfsZipFileSourceFactory`, `VfsTarFileSourceFactory`, `VfsHttpFileSourceFactory`, `VfsWebdavFileSourceFactory` ... | `VfsFileSourceFactory` | One small class per scheme |

What a scheme's subclass supplies (everything else is shared):

1. `getTypeId()`: the registry key and URL scheme, `vfs` + the VFS scheme (`vfssftp`, `vfszip` ...).
2. `vfsScheme()`: the VFS scheme it opens (`sftp`, `zip` ...): the type id without its `vfs` prefix, by default.
3. `getConnectionSettings()`: its settings (host, port, user, password; or, for an archive, the archive).
4. `rootUri()`: the VFS URI of the file system's root, built from the settings (`sftp://tony@host:22/`,
   `zip:file:///tmp/a.zip!/`).
5. `configure(FileSystemOptions)`: its scheme's options (sftp: `setUserDirIsRoot(false)`, timeouts, host keys).
6. Optionally `posixMode(FileObject)`: the file's nine permission bits where the scheme's provider has them (sftp,
   ftp), else null (section 5.3).
7. Optionally `getCapabilities()` adjustments a scheme knows better than VFS (e.g. `orderedOperations`).

The registration file `META-INF/services/us.bringardner.parley.files.FileSourceFactory` lists each subclass, as
parley-files-ftp lists `FtpFileSourceFactory` and `FtpsFileSourceFactory`.

## 4. VfsFileSourceFactory

### 4.1 One file system per factory

Parley's rule is that a factory is a file system and a path means something only on it. So a VFS factory is one VFS
file system, identified by its root URI:

1. sftp, ftp, ftps, WebDAV: one server account (host, port, user). Paths are absolute from the server's root.
2. An archive: one archive. The setting is where the archive is (a VFS URI such as `file:///tmp/a.zip`, later a
   FileSource: section 8, Q4); paths are inside it (`/dir/a.txt`).
3. http, https: one site (scheme, host, port); paths are the URL paths.

### 4.2 Connection lifecycle

1. `connectImpl()`: create a `StandardFileSystemManager` of the factory's own and `init()` it (never
   `VFS.getManager()`, see 2.2.5); route its log through `VfsLog`; build `FileSystemOptions`
   (authenticator + `configure`); `root = manager.resolveFile(rootUri(), options)`; touch `root.exists()` so a wrong
   host or password fails the connect, not the first file call.
2. `disConnectImpl()`: `manager.close()` (closes every file system and stream it opened); forget `root`.
3. `isConnected()`: a manager and a root are there. VFS reconnects an sftp session that dropped on the next call, so
   no ping.
4. `createThreadSafeCopy()`: a new instance of the same class with the same settings and its own manager (so its own
   connection), as `FtpFileSourceFactory.newCopy()` does.
5. `isSameFileSystem(other)`: same class and same `root.getName().getRootURI()` (scheme, user, host, port; for an
   archive, the archive).

### 4.3 Settings and secrets

1. Settings are the usual `ConnectionSetting`s (`host`, `port`, `user`, `password`, `timeout`; per scheme more), read
   and written by `setConnectionProperties(Properties)` / `getConnectProperties()`. Names follow the 2.0.0 rule:
   JVM-wide tuning defaults under `parley.files.vfs.*`, a connection's own settings only from the connection.
2. The password may be a keychain reference. `VfsAuthenticator` implements VFS's `UserAuthenticator`: VFS asks it for
   the user and password when it opens a connection, and it resolves the reference then through
   `FileSourceFactory.secret()` and hands VFS a `char[]` (`UserAuthenticationData` takes one). So no password sits in
   a `String` in the options, which `StaticUserAuthenticator` would need.
3. `isSecretProperty("password")` is true, so dialogs and saved connections treat it as a secret.

### 4.4 sftp (`VfsSftpFileSourceFactory`, the first scheme)

1. Options: `setUserDirIsRoot(false)` (2.2.4); connect timeout from `connectTimeout`.
2. Settings: parley-files-sftp's own, with the same names and defaults, so a saved sftp connection works as a
   vfssftp one and 17.2 compares like with like: `host`, `port` (22), `user`, `auth`, `password`, `identityFile`,
   `privateKey`, `knownHosts`, `strictHostKeyChecking` (`yes`), `connectTimeout`.
3. Keys: the key (from `privateKey`, or read from `identityFile`) is given to VFS as bytes (`BytesIdentityInfo`).
4. Host keys (Q5, decided 2026-11-13): as parley-files-sftp does them. `knownHosts` is a local file
   (default `~/.ssh/known_hosts`), given to VFS with `setKnownHosts(File)`; `strictHostKeyChecking` (default `yes`)
   with `setStrictHostKeyChecking`. parley-files-sftp's `knownHosts` is already a local file setting
   (`ConnectionSetting.localFile`), so this adds no new local-file case, and it uses VFS's own options with nothing
   reaching into VFS's JSch. Not chosen: setting a `HostKeyRepository` on VFS's `JSch` from inside
   `setIdentityRepositoryFactory` (that hook is meant for identities, so it would be a side effect VFS doesn't promise
   to keep), and a hidden `FileProxy` copy of the known hosts (more code for the same file).
5. Agent login (`auth` = Agent): later, through `setIdentityRepositoryFactory` with JSch's agent support.
6. JSch: the maintained `com.github.mwiede:jsch`, as parley-files-sftp-jsch uses, which makes `vfssftp` against
   `sftp-jsch` the cleanest 17.2 pair.

### 4.5 The rest of FileSourceFactory

| FileSourceFactory | With VFS |
|---|---|
| `createFileSource(path)` | `root.getFileSystem().resolveFile(path)` for an absolute path; a relative one from `getCurrentDirectory()` |
| `listRoots()` | `{ root }` |
| `getCurrentDirectory()` / `setCurrentDirectory()` | The path in the root URI (often `/`); kept per factory, never changes shared state |
| `getTypeId()` | The subclass's (section 3) |
| `getSeparatorChar()` / `getPathSeparatorChar()` | `/` and `:` |
| `getTitle()` / `getURL()` | `root.getName().getFriendlyURI()`, which leaves out the password (parley-files-ftp's `getURL()` includes it) |
| `setConnectionProperties(URL)` | Parse the VFS URL into the settings (see 6) |
| `createSymbolicLink()` / `createLink()` | `UnsupportedOperationException`: VFS can't make links |
| `whoAmI()` | The user from the settings |
| `isVersionSupported()` | false |

## 5. VfsFileSource

A `VfsFileSource` holds its factory and its `FileObject` (resolved once). Every VFS `FileSystemException` is an
`IOException`, so it passes through.

### 5.1 Names and paths

| FileSource | With VFS |
|---|---|
| `getName()` | `fo.getName().getBaseName()` |
| `getAbsolutePath()` / `getPath()` | `fo.getName().getPathDecoded()` (the path in the file system, not the URI) |
| `getCanonicalPath()` | The same: VFS normalises `.` and `..`, and can't resolve links, so it is lexical, as in parley-files-ftp |
| `getParent()` / `getParentFile()` | `fo.getParent()` (null at the root) |
| `getChild(path)` | `fo.resolveFile(path)` |
| `toURL()` | A `filesource:` URL with `sourcetype=<type id>`, without the password, as parley-files-sftp makes (`filesource://user@host:port/path?sourcetype=vfssftp`; parley-files-ftp's carries the password); an archive's will carry the archive as a setting. `getVfsUri()` added for the plain VFS URI |
| `getTitle()` | `fo.getName().getFriendlyURI()` |

### 5.2 Type and attributes

| FileSource | With VFS |
|---|---|
| `exists()` | `fo.exists()` |
| `isFile()` / `isDirectory()` | `fo.isFile()` / `fo.isFolder()` |
| `isHidden()` | `fo.isHidden()` |
| `length()` | `fo.getContent().getSize()` for a file, 0 for a folder |
| `lastModified()` | `getContent().getLastModifiedTime()` if `GET_LAST_MODIFIED`, else 0 |
| `creationTime()`, `lastAccessTime()`, `getCreateDate()` | From `getContent().getAttributes()` where a scheme has them, else 0 |
| `setLastModifiedTime(t)` | `setLastModifiedTime` if `SET_LAST_MODIFIED_FILE` / `_FOLDER` (by type), else false |
| `setLastAccessTime`, `setCreateTime` | false |
| `getContentType()` | `getContent().getContentInfo().getContentType()`, else parley-files' table by extension |
| `getOwner()` / `getGroup()` | A principal with a null name (unknown), as parley-files-ftp does when the server doesn't say |
| `getLinkedTo()` | null (2.2.8); `isSymbolicLink()` noted for a later `isLink` |
| versions | `isVersionSupported()` false, the rest 0 / false |

### 5.3 Permissions

Decided 2026-11-13 (Q2): as complete as VFS allows, and false for anything VFS doesn't give.

VFS's API gives only the connected user's access (2.2.7). Inside, the sftp and ftp providers keep the file's full mode
(sftp: `SftpFileObject.getPermissions(false)`, a protected method returning `PosixPermissions`; ftp: commons-net's
`FTPFile` in a private field). A scheme's `posixMode(FileObject)` hook reads it by reflection; the other schemes have
no hook.

| FileSource | With VFS |
|---|---|
| `canRead()`, `canWrite()`, `canExecute()` | `fo.isReadable()`, `fo.isWriteable()`, `fo.isExecutable()`: VFS's own answer for the connected user (overridden, since FileSource's defaults work them out from the bits) |
| `canOwner*()`, `canGroup*()`, `canOther*()` | The bit from `posixMode()`; false when the scheme has no hook or the hook can't read it |
| `setReadable(b, ownerOnly)` and the like | `fo.setReadable(b, ownerOnly)` (ownerOnly false sets owner, group and other) |
| `setOwnerReadable(b)` and the like | `fo.setReadable(b, true)` (owner only) |
| `setGroup*()`, `setOther*()` | false: VFS can't set group or other alone |
| `setReadOnly()` | `fo.setWritable(false, false)` |

The hooks reach VFS internals, so:

1. A failure (the method or field isn't there in another VFS version) gives null, so false bits, never an exception;
   it is logged once.
2. A test per hook checks the bits against what the test server set (chmod), so a VFS upgrade that breaks one is
   caught.
3. sftp's `canRead()` and the like come from VFS running `id -u` / `id -G` on the server (README, "What VFS does",
   item 8); on an SFTP-only server VFS takes the user as owner. The bits themselves come from the server's stat and
   don't depend on that.

### 5.4 Create, delete, rename

| FileSource | With VFS |
|---|---|
| `createNewFile()` | false if `exists()`; false if the parent isn't a folder; else `fo.createFile()`, true. Not atomic |
| `mkdir()` | false if `exists()` or the parent doesn't exist; else `fo.createFolder()`, and false if that fails because someone else made it meanwhile. Not claimed atomic |
| `mkdirs()` | false if `exists()`; else `fo.createFolder()` |
| `delete()` | `fo.delete()` (a non-empty folder is not deleted, as with `java.io.File`) |
| `renameTo(dest)` | Only on the same factory (`isSameFileSystem`) and when `fo.canRenameTo(destFo)`; false if dest exists (VFS would delete it, 2.2.1); else `fo.moveTo(destFo)` |
| `renameTo(dest, true)` | Delete dest if it is a file (or empty folder), then rename: not atomic, as in parley-files-ftp. Never `deleteAll` |

Every write needs the scheme's capability (`CREATE`, `DELETE`, `RENAME`, `WRITE_CONTENT`). Without it the call throws
an `IOException` that says the file system is read only, rather than VFS's message.

### 5.5 Listing

| FileSource | With VFS |
|---|---|
| `list()` / `listFiles()` | `fo.getChildren()`: one call, and for sftp, ftp and WebDAV the children come with their attributes |
| with a filter | The same, filtered here |
| `listFiles(progress)` | The same, reporting each child |
| `dereferenceChilderen()` | Drop the children kept |
| without `LIST_CHILDREN` (http) | An empty list, for now (Q6, decided 2026-11-13) |

### 5.6 Streams

| FileSource | With VFS |
|---|---|
| `getInputStream()` | `getContent().getInputStream(bufferSize)` |
| `getInputStream(pos)` | `RandomAccessContent(READ)`, `seek(pos)`, its `getInputStream()` if `RANDOM_ACCESS_READ`; else open and skip |
| `getOutputStream(append)` | `getContent().getOutputStream(append, bufferSize)`; `append` needs `APPEND_CONTENT` |
| `getSeekableInputStream()` | `VfsSeekableInputStream` over `RandomAccessContent(READ)` |
| `getRandomAccessStream(mode)` | `VfsRandomAccessIoController`: `readChunkForPos` = seek + read, `writeChunk` = seek + write, `setLength0` = `setLength`. `"r"` needs `RANDOM_ACCESS_READ`; `"rw"` needs `RANDOM_ACCESS_WRITE`, which no remote scheme has (2.1.2) |

VFS keeps a file's open streams with its `FileContent` per thread, and closing a `FileContent` closes them all. So each
stream is closed on its own, and `FileContent.close()` is never called while one may be open.

### 5.7 Locks and capabilities

1. Locks: FileSource's in-process locks (`LockScope.PROCESS`): the defaults, nothing to write.
2. `getCapabilities()`: `defaults(PROCESS).withAtomicCreateNewFile(false).withAtomicMkdir(false)`; atomic replace
   and durable sync false; `orderedOperations` true for one server (sftp, ftp, WebDAV) and an archive, as
   parley-files-ftp says for FTP.

## 6. Addressing by URL (decision 1.3; Q3 and Q8)

### 6.1 URL handlers and type ids (Q3 and Q9, decided 2026-11-13)

1. Each scheme's type id and URL scheme are `vfs` + the VFS scheme: `vfszip`, `vfstar`, `vfstgz`, `vfshttp`,
   `vfssftp` ... The JDK finds the URL handler as `us.bringardner.parley.files.vfszip.Handler` and so on.
2. The handler's URL is the VFS URL with `vfs` in front: `vfszip:file:///tmp/a.zip!/dir` is VFS's
   `zip:file:///tmp/a.zip!/dir`.
3. The prefix removes the problems a plain scheme had: no package name has a `-` in it, none takes over a scheme the
   JDK owns (`http`, `https`, `jar`, `file`), and none clashes with Parley's own `sftp`, `ftp` and `ftps`. So every
   scheme can have a handler.

### 6.2 How a URL reaches a file (Q8)

`FileSourceFactory.getFileSource(URL)` in parley-files already does the whole job for any scheme with a handler:

1. finds the factory class by the URL's scheme (`getFileSourceFactory(URI)`, via `FileSourceUri.getFactoryId()`);
2. calls the factory's `setConnectionProperties(URL)`, which sets the settings from the URL;
3. connects, then calls `createFileSource(path)` with the path `FileSourceUri` takes from the URL.

So with the handlers of 6.1, `FileSourceFactory.getFileSource("vfszip:file:///tmp/a.zip!/dir")` works with **no change
to parley-files**, on two conditions (`new URL("vfszip:...").openConnection()` needs this module's own
`VfsUrlConnection`, found when built: section 9):

1. **The factory's type id is the URL's scheme** (`vfszip`), since step 1 looks the factory up by it (Q9, decided).
2. **The factory takes what `FileSourceUri` calls the path.** A nested URL (`vfszip:file:///tmp/a.zip!/dir`) is an
   opaque URI, and for one of those `FileSourceUri` gives the whole inner part (`file:///tmp/a.zip!/dir`) as the path.
   `VfsFileSourceFactory.createFileSource` therefore also accepts a value that starts with its root URI and takes the
   rest as the path. A server URL (`vfssftp://tony@host/home/tony`) is hierarchical and gives a plain path.

Decided 2026-11-13 (Q8): this way, with no change to parley-files. What that leaves of the two other options:

1. **`VfsFileSourceFactory.open(String url)` in this module** is no longer needed for URLs with a handler; it would
   only save the caller from building a `java.net.URL`. Not proposed.
2. **Changing `FileSource.of(URI)` in parley-files** to hand any registered scheme to `getFileSource(uri.toURL())`
   (today it takes only `filesource:` and `file:`) would let `FileSource.of(URI)` open every provider's URLs (`ftp:`,
   `sftp:`, `vfszip:` ...), not just VFS's. It is a parley-files change, so it is only noted here, for you to raise if
   wanted; parley-files-vfs doesn't need it.

A `filesource:` URL (`filesource:/dir?sourcetype=vfszip&archive=...`) works as for every other provider.

## 7. Tests

1. `TestVfsSftp extends AbstractTestClass`, the shared suite, against a test SFTP server started in the test (as
   parley-files-sftp's tests do); then `AbstractRandomAccessStreamTests` (read only), `AbstractNioProviderTests` and
   `AbstractLockTests`. These are also 17.2's correctness runs.
2. Tests of the VFS traps in 2.2: rename onto an existing file returns false and leaves both; `mkdir` without its
   parent returns false; `createNewFile` on an existing file returns false and doesn't truncate it.
3. A read-only scheme (zip from a test archive) through the shared read tests, and a write on it giving the read-only
   `IOException`.
4. No leaks: every test closes its streams and disconnects its factories (`try`-with-resources and `@AfterAll`).

## 8. Decisions (2026-11-13) and what is still open

1. **Q1 caching: decided, VFS's default.** `ON_RESOLVE` with the soft cache; `FileSource.refresh()` calls
   `fo.refresh()`, and a change made through the factory refreshes what it touched. To be measured in 17.2.
2. **Q2 permissions: decided, as complete as VFS allows, false for anything it doesn't give** (5.3).
3. **Q3 URL handlers: decided for now, `vfs` + scheme** (`vfszip`, `vfstar` ...; 6.1).
4. **Q4 archives on a FileSource: later, recorded.** A small VFS provider for a `parley:` scheme (a VFS `FileObject`
   over a FileSource) would let VFS open `zip:parley:...`, so an archive on any Parley file system (sftp, a database
   ...) can be read, keeping to Parley's rule of FileSource over files. After the first schemes work.
5. **Q5 sftp host keys: decided, as parley-files-sftp does them.** `knownHosts` a local file (default
   `~/.ssh/known_hosts`) and `strictHostKeyChecking` (default `yes`), through VFS's own options (4.4.4).
6. **Q6 a scheme that can't list (http): decided for now, an empty list** (5.5).
7. **Q7 GraalVM: later.** VFS finds its providers from `providers.xml` by reflection; to be checked before
   parley-files-vfs goes into fsh's native build.
8. **Q8 plain VFS URLs: decided, as 6.2 works it out.** The handlers of Q3 and parley-files' existing
   `getFileSource(URL)` open a VFS URL with no parley-files change; no `open(url)` in this module. Changing
   `FileSource.of(URI)` to take every provider's URLs is noted, not needed.
9. **Q9 type ids: decided, `vfs` + the VFS scheme**, the same as the URL scheme: `vfszip`, `vfstar`, `vfshttp`,
   `vfssftp`, `vfsftp`, `vfsftps` ... Replaces 17.1's "type id = the VFS scheme" (`zip`) and 17.2's `vfs-sftp`,
   `vfs-ftp`, `vfs-ftps` (17.1 was preliminary). One rule for every scheme, and never a clash with Parley's own `sftp`,
   `ftp` and `ftps`.

## 9. Built (2026-11-13): the shared base and vfssftp

Branch `vfs-project`. Built as sections 3 to 7 describe, with these differences, found while building:

1. **`openConnection()`** (6.2): parley-files' `FileSourceURLConnection` takes only `filesource:` URLs, so the
   `vfssftp` handler returns `VfsUrlConnection`, which opens the file with `FileSourceFactory.getFileSource(URL)` and
   disconnects that factory when its stream is closed. No parley-files change. (parley-files-sftp's and
   parley-files-ftp's handlers return `FileSourceURLConnection`, so their `openConnection()` fails the same way.)
2. **`toURL()`** leaves out the password, as parley-files-sftp's does (5.1). The password can be given in the query
   (`&password=...`) or come from a key.
3. **`setConnectionProperties(URL)`** also takes any of the factory's settings named in the URL's query
   (`?strictHostKeyChecking=no`).
4. **`mkdir()`** returns false when VFS's create fails because someone else made the folder meanwhile (5.4).
   `createNewFile()` and `mkdir()` are still not claimed atomic.
5. **Password logins give VFS no identities**: otherwise VFS's sftp provider tries `~/.ssh/id_rsa` first.
   `preferredAuthentications` is `password,keyboard-interactive` for a password and `publickey` for a key.
6. **No generation counter**: a `VfsFileSource` resolves its `FileObject` again when it belongs to a file system the
   factory no longer has (after a reconnect).
7. **Tests** (7) run against an Apache MINA SFTP server each test class starts. `AbstractRandomAccessStreamTests`
   writes at positions, which VFS's sftp can't, so `VfsSftpRandomAccessTest` checks reading at positions instead.
   Of the shared suites, these are skipped, each saying why: `testFileAttributes` and `testCreateDir` (they set the
   group's and other users' bits alone: Q2), `fileChannelMapsPrivateAndReadWrite` (writes at a position: the suite
   skips it itself), `transferToStopsWhenTheTargetTakesNothing` (reads in one thread and closes in another: JSch
   drops a stream whose reading thread has ended, README item 10), and the 11 cross-process lock cases. 79 tests,
   15 skipped, none failing.
8. **The permission hook** (5.3) works on VFS 2.10.0: `VfsSftpPermissionBitsTest` sets bits with chmod on the
   server's directory and reads them back.

## 10. Built (2026-11-13): vfsftp and vfsftps

Branch `vfs-project`, the next schemes after `vfssftp` (decision 1.8), which To-Do 17.2 compares with parley-files-ftp's
`ftp` and `ftps`. Each is a small subclass, as section 3 has it: `VfsFtpFileSourceFactory` (`vfsftp`) and
`VfsFtpsFileSourceFactory` (`vfsftps`, extending it), with `vfsftp:` and `vfsftps:` URL handlers. Decided while
building, each following a rule already made (parley-files-ftp's settings, as `vfssftp` took parley-files-sftp's;
the two types as parley-files-ftp has them; 17.2's "same server"):

### 10.1 Settings and types

1. **parley-files-ftp's settings, names and defaults**, so a saved ftp connection works as a vfsftp one: `host`,
   `port` (21; 990 for vfsftps), `user`, `password`, `secure` (vfsftp only, default false) and `timeout` (4s: VFS's
   connect, reply and data timeouts).
2. **The two types as parley-files-ftp has them**: `vfsftp` with `secure` true is explicit TLS (AUTH TLS before
   logging in), `vfsftps` is implicit TLS from the first byte. VFS's own ftps scheme does both (its default is
   explicit), so `vfsftp` with TLS opens VFS's `ftps` scheme in explicit mode, and `vfsftps` the same scheme in implicit
   mode. `vfsScheme()` is `ftps` whenever TLS is on.
3. **`getURL()` is the type's own** (`vfsftp://ftp@host:21/`, and `?secure=true` for explicit TLS), not `vfs` + VFS's
   root URI, which for explicit TLS would be `vfsftps:`, an implicit TLS URL.
4. **Not offered** from parley-files-ftp's settings: `account` (VFS logs in with user and password only; it can't send
   ACCT), `storeAtOffset` (VFS's ftp can't write at a position) and `bufferSize` (a programmer's setting there). A
   saved connection that has them still opens; they are ignored.
5. No user logs in as anonymous, as VFS does.
6. `commons-net` is an optional dependency of the module (compiled against for the listing entry's bits, 10.3, and
   not passed on); `FTPFile` is used only from a nested class, so the registry can load the factories without it.

### 10.2 VFS's defaults changed

VFS's ftp, left to its defaults, behaves unlike parley-files-ftp and, with TLS, unsafely. Each is set in `configure`:

| VFS default | Set to | Why |
|---|---|---|
| Active mode (no PASV) | Passive | As parley-files-ftp, and what works through firewalls and NAT |
| `userDirIsRoot` true: `/` is the login directory | false | Paths are the server's, as in parley-files-ftp (as for sftp, 2.2.4) |
| The listing's time (to the minute, often only the day) | MDTM for files (to the second) | parley-files-ftp's times; see 10.3 for directories |
| ISO-8859-1 file names | UTF-8, and OPTS UTF8 where the server lists it | Names as Parley's client and server send them |
| A stream closed early is a failed close | 426 and 550 after an early close are fine (VFS's own "sane" list) | Otherwise `head()`, or reading part of a file, throws |
| TLS: no PROT | PBSZ 0, PROT P | VFS sent no PROT, so files and listings went **in the clear** after a TLS login; against Parley's implicit-TLS server a listing hung |
| TLS: commons-net's trust manager, which only checks that the certificate is in date | `VfsTrustManager` | VFS **accepted any self-signed certificate, for any host** (checked: it logged in to a server the JVM doesn't trust, at a host the certificate doesn't name) |

`VfsTrustManager` checks the chain as Parley's FTP client does: with `SecureBaseObject.getDefaultTrustManagers()`
when an application has set them (parley-files-viewer's certificate dialog does), else the JVM's trust store; and the
certificate must be for the factory's host (RFC 6125: an address literal against its IP addresses, a name against its
DNS names with `*` standing for one whole first label, or its common name when it has no DNS names). The host is the
one the factory was given, not the one each socket names: an FTP data connection goes to the address PASV gives, and
must present the same server's certificate. VFS has no option for commons-net's own host name check, so this is in
the trust manager. No setting turns the check off (parley-files-ftp has none either).

### 10.3 Where VFS's ftp needed help

1. **Permission bits** (5.3, Q2): VFS's ftp provider keeps the server's listing entry (commons-net's `FTPFile`) in a
   private field, `ftpFile`; `posixMode` reads the nine bits from it by reflection. A failure gives false bits, logged
   once. VFS's ftp can't set any bit (it has no SITE CHMOD), so every setter returns false.
2. **A directory's time**: with MDTM on, VFS asks MDTM for a directory too, which servers answer only for files (RFC
   3659), and then fails (commons-net gives no time and VFS doesn't check: a NullPointerException inside a
   `FileSystemException`). A new base hook, `lastModified(FileObject)`, lets vfsftp take a directory's time from its
   listing entry (the same field) and a file's from VFS.
3. Times can't be set (VFS's ftp has no `SET_LAST_MODIFIED`): `setLastModifiedTime` returns false.

### 10.4 Tests

Against Parley's own FtpServer (parley-ftp, test scope), which each test class starts on a free port with its own
root, anonymous FTP with read and write: the server parley-files-ftp's tests use, so 17.2 compares like with like.

1. The tests every scheme shares are now abstract bases each scheme's test server plugs into (`VfsTestServer`):
   `AbstractVfsTrapsTest`, `AbstractVfsRandomAccessTest`, `AbstractVfsLockTest`, `AbstractVfsPermissionBitsTest`. The
   vfssftp tests extend them too, unchanged in what they test.
2. vfsftp: the shared suites (`TestVfsFtp`, `VfsFtpNioProviderTests`, `VfsFtpLockTest`), the traps, random access
   reading, the permission bits, the factory (URLs, settings, the VFS options of 10.2, copies, reconnects, a
   directory's time).
3. TLS (`VfsFtpTlsTest`): explicit and implicit TLS listing, reading and writing; a certificate the JVM doesn't trust
   refused; a trusted certificate for another host refused (for both types); PROT P and the mode in the options.
   `VfsTrustManagerTest`: the host name rules.
4. Skipped, each saying why: setting permission bits in the NIO suite (VFS's ftp can't), the forced write visible
   before close (an FTP upload is stored when it ends, as parley-files-ftp's NIO tests say too), and the cross-process
   lock cases. As root, `VfsFtpPermissionBitsTest` is skipped: Parley's FtpServer lists the bits its FileProxy reports,
   and for root every file is writable by its owner (parley-files-ftp's tests need an ordinary user too).
5. Results: as an ordinary user 166 tests, 30 skipped, 1 failing, `TestVfsSftp.testPermissions` (10.5); as root
   160, 30 skipped, none failing.

### 10.5 Found: vfssftp's permission setters (open)

Running the whole suite as an ordinary user (it had been run only as root) shows `TestVfsSftp.testPermissions`
failing, on the branch as it was before this work too. VFS's sftp changes a permission by sending the file's whole
stat back (size, owner and group, times, mode), not a chmod. The MINA test server refuses the times part on a file its
owner can't read (Java sets times through a read handle); OpenSSH would allow it. Re-sending the size and times also
means a file that grows between VFS's stat and its setstat could be cut back. Open: whether vfssftp should send only the
mode (a plain chmod, reaching VFS internals as the bits hook does), or keep VFS's way and skip that case in the test.

