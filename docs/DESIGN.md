# parley-files-vfs: how FileSourceFactory and FileSource map onto Commons VFS

As of 2026-11-13, against Apache Commons VFS 2.10.0 (the current release, Java 8+) and parley-files 2.0.0-SNAPSHOT
(release-2.0.0). Nothing here is implemented yet. Sections 1 and 2 are the decisions already in the Parley 2.0.0 To-Do
doc (17.1, 17.2) and what VFS itself does; sections 3 to 7 are the proposed design; section 8 lists what is still to
decide.

## 1. Decisions already made (To-Do 17.1 and 17.2, 2026-11-07)

1. Read and write.
2. One factory per VFS scheme. Its type id is the scheme (`zip`, `tar`, `http`, `webdav4` ...), because the registry
   maps one type id to one class. Almost all the code is shared; each scheme's factory is a small subclass.
3. Files are addressed by the plain VFS URL, e.g. `zip:file:///tmp/a.zip!/dir`.
4. One direct dependency, `commons-vfs2`. A scheme's own library (JSch, commons-compress, httpclient5 ...) is optional
   and added by whoever uses that scheme.
5. What a scheme can do (write, random access ...) is read from VFS's capabilities at run time, not coded per scheme.
   Once one scheme works, a further scheme is little more than configuration: its type id and connection settings.
6. Lock scope `PROCESS`; capabilities conservative (no atomic or durable guarantees claimed).
7. VFS schemes Parley already has (`sftp`, `ftp`, `ftps`) would clash in the registry. For the 17.2 comparison VFS's
   are registered as `vfs-sftp`, `vfs-ftp` and `vfs-ftps`; the comparison decides what is exposed after that.
8. Build order: the shared base with `vfs-sftp` first, then the other schemes.

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
| `VfsSftpFileSourceFactory` | `VfsFileSourceFactory` | Type id `vfs-sftp`: the first scheme |
| later: `VfsFtpFileSourceFactory`, `VfsFtpsFileSourceFactory`, `VfsZipFileSourceFactory`, `VfsTarFileSourceFactory`, `VfsHttpFileSourceFactory`, `VfsWebdavFileSourceFactory` ... | `VfsFileSourceFactory` | One small class per scheme |

What a scheme's subclass supplies (everything else is shared):

1. `getTypeId()`: the registry key (`vfs-sftp`, `zip` ...).
2. `vfsScheme()`: the VFS scheme it opens (`sftp`, `zip` ...); usually the type id without a `vfs-` prefix.
3. `getConnectionSettings()`: its settings (host, port, user, password; or, for an archive, the archive).
4. `rootUri()`: the VFS URI of the file system's root, built from the settings (`sftp://tony@host:22/`,
   `zip:file:///tmp/a.zip!/`).
5. `configure(FileSystemOptions)`: its scheme's options (sftp: `setUserDirIsRoot(false)`, timeouts, host keys).
6. Optionally `getCapabilities()` adjustments a scheme knows better than VFS (e.g. `orderedOperations`).

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

1. Options: `setUserDirIsRoot(false)` (2.2.4); connect and session timeouts from `timeout`.
2. Keys: read the private key from a FileSource into bytes and give it as `BytesIdentityInfo`, so no local file is
   needed.
3. Host keys: VFS takes known hosts only as a `java.io.File`. Two ways out, to decide (section 8, Q5): use
   parley-files-sftp's host-key handling through `setIdentityRepositoryFactory`, which receives VFS's `JSch` instance
   (we can set its `HostKeyRepository` there); or write the known hosts to a `FileProxy` temp file, hidden from the
   caller (Parley's rule for cases that need a real file).
4. JSch: the maintained `com.github.mwiede:jsch`, as parley-files-sftp-jsch uses, which makes `vfs-sftp` against
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
| `toURL()` | A `filesource:` URL with `sourcetype=<type id>`, as parley-files-ftp makes (`filesource://user:pw@host/path?sourcetype=ftp`); an archive's carries the archive as a setting. `getVfsUri()` added for the plain VFS URI |
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

VFS knows only the connected user's access (2.2.7):

| FileSource | With VFS |
|---|---|
| `canRead()`, `canWrite()`, `canExecute()` | `fo.isReadable()`, `fo.isWriteable()`, `fo.isExecutable()` (overridden, since FileSource's defaults work them out from the bits) |
| `canOwnerRead()` and the other owner bits | Proposed: the same as `canRead()` ... (section 8, Q2) |
| `canGroup*()`, `canOther*()` | Proposed: false |
| `setReadable(b, ownerOnly)` and the like | `fo.setReadable(b, ownerOnly)` |
| `setOwnerReadable(b)` and the like | `fo.setReadable(b, true)` |
| `setGroup*()`, `setOther*()` | false (VFS can't) |
| `setReadOnly()` | `fo.setWritable(false, false)` |

### 5.4 Create, delete, rename

| FileSource | With VFS |
|---|---|
| `createNewFile()` | false if `exists()`; false if the parent isn't a folder; else `fo.createFile()`, true. Not atomic |
| `mkdir()` | false if `exists()` or the parent doesn't exist; else `fo.createFolder()`. Not atomic |
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
| without `LIST_CHILDREN` (http) | `IOException` (or an empty list: section 8, Q6) |

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

## 6. Addressing by the plain VFS URL (decision 1.3)

1. The outer scheme of a VFS URL is the type id (`zip:file:///tmp/a.zip!/dir` is `zip`), and the factory splits the
   URL with VFS's own parser (`manager.resolveURI`) into its root (the settings) and the path.
2. Today `FileSourceFactory.getFileSourceFactory(URI)` finds the factory class by the scheme but returns it
   unconfigured, and `FileSource.of(URI)` accepts only `filesource:` and `file:` URIs. So a plain VFS URL needs one
   of (section 8, Q8):
    1. an entry point in this module, `VfsFileSourceFactory.open(String vfsUrl)`: finds the factory by scheme, sets
       its settings from the URL, connects, returns the FileSource; no change to parley-files;
    2. a parley-files change: `FileSource.of(URI)` hands any other registered scheme to its factory to configure
       from the URI. Every provider would gain it, but it is a change to parley-files, to discuss first.
3. `java.net.URL` needs a handler per scheme, found as `us.bringardner.parley.files.<scheme>.Handler`. That works for
   `zip`, `tar` and `tgz`, but **not** for:
    1. `vfs-sftp`, `vfs-ftp`, `vfs-ftps`: a package name can't have a `-`;
    2. `http`, `https`, `jar` and `file`: those are the JDK's own, and taking them over would change every URL in the
       JVM.

   So those are addressed by URI or by a `filesource:` URL (`filesource:/path?sourcetype=vfs-sftp&host=...`), never by
   a `java.net.URL` of their own.

## 7. Tests

1. `TestVfsSftp extends AbstractTestClass`, the shared suite, against a test SFTP server started in the test (as
   parley-files-sftp's tests do); then `AbstractRandomAccessStreamTests` (read only), `AbstractNioProviderTests` and
   `AbstractLockTests`. These are also 17.2's correctness runs.
2. Tests of the VFS traps in 2.2: rename onto an existing file returns false and leaves both; `mkdir` without its
   parent returns false; `createNewFile` on an existing file returns false and doesn't truncate it.
3. A read-only scheme (zip from a test archive) through the shared read tests, and a write on it giving the read-only
   `IOException`.
4. No leaks: every test closes its streams and disconnects its factories (`try`-with-resources and `@AfterAll`).

## 8. Still to decide

1. **Q1 caching.** VFS's default (`ON_RESOLVE`, soft cache) keeps a `FileObject`'s attributes until `refresh()`.
   Proposed: keep it, have `FileSource.refresh()` call `fo.refresh()`, and refresh after each change made through the
   factory; measure in 17.2 against parley-files-ftp's 2 s attribute cache before adding a TTL of our own.
2. **Q2 permission bits.** Owner bits = the connected user's access, group and other false (5.3)? Or owner bits too
   false, so nothing claims what VFS doesn't know?
3. **Q3 URL handlers.** Provide `Handler` classes for the schemes that can have one (`zip`, `tar`, `tgz` ...), or none,
   so every VFS file is reached the same way (URI or `filesource:` URL)?
4. **Q4 archives on a FileSource.** Parley uses FileSource instead of files. A small VFS provider for a `parley:` scheme
   (a VFS `FileObject` over a FileSource) would let VFS open `zip:parley:...`, so an archive on any Parley file system
   (sftp, a database ...) can be read. Proposed for after the first schemes.
5. **Q5 sftp host keys.** Through VFS's `JSch` instance with parley-files-sftp's host-key handling, or a hidden
   `FileProxy` known-hosts file (4.4.3)?
6. **Q6 a scheme that can't list (http).** Throw `IOException` from `list()`, or return an empty list as
   `java.io.File` does for something it can't read?
7. **Q7 GraalVM.** VFS finds its providers from `providers.xml` by reflection. Not a native-image target now; to be
   checked before parley-files-vfs goes into fsh's native build.
8. **Q8 plain VFS URLs.** `VfsFileSourceFactory.open(vfsUrl)` in this module, or a change to parley-files'
   `FileSource.of(URI)` (6.2)?
