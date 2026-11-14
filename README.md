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
| `vfsfile` (`file`) | nothing | yes (random access: read and write) |
| `vfstmp`, `vfsram` (`tmp`, `ram`) | nothing | yes (random access: read and write) |
| `vfsres` (`res`) | nothing | no (read only) |
| `vfszip`, `vfsjar` (`zip`, `jar`) | nothing | no (read only) |
| `vfstar`, `vfstgz`, `vfstbz2` (`tar`, `tgz`, `tbz2`) | `org.apache.commons:commons-compress` | no (read only) |
| `vfsgz` (`gz`) | nothing | its one file |
| `vfsbz2` (`bz2`) | `org.apache.commons:commons-compress` | its one file |
| `vfshttp`, `vfshttps` (`http`, `https`) | `org.apache.httpcomponents.client5:httpclient5` | no (read only; folders from the server's listing pages) |
| `vfshdfs` (`hdfs`) | `org.apache.commons:commons-vfs2-hdfs` and Hadoop's client (`org.apache.hadoop:hadoop-client`): VFS's pom drops Hadoop's own dependencies | yes (random access: read only) |
| `vfswebdav`, `vfswebdavs` (`webdav4`, `webdav4s`) | `org.apache.commons:commons-vfs2-jackrabbit2` (VFS's WebDAV provider is its own artifact; it brings httpclient 4 and jackrabbit-webdav) | yes, no append (random access: read only) |

Each type id is `vfs` + the VFS scheme (for WebDAV, `vfswebdav` on VFS's current `webdav4`), and is also the scheme of its URLs: `vfszip:file:///tmp/a.zip!/dir` is VFS's
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

Every permission bit can be set, each alone (a chmod with only the mode, as parley-files-sftp sends). What it can't
do, from VFS: write at a position in a file (`getRandomAccessStream("rw")` is refused; `"r"` works), make links, or say
who owns a file. `createNewFile()` and `mkdir()` are **not atomic**
(`getCapabilities()` says so).

It logs in with a password, a key file (`identityFile`, OpenSSH or PEM), a pasted key (`privateKey`), or the keys a
running ssh-agent holds (`auth` = `Agent`: SSH_AUTH_SOCK, or the Windows OpenSSH agent; Java 16 or later on macOS and
Linux), as parley-files-sftp's jsch provider does. A key or agent login tries only keys, never the password.

### vfssftp's deficiencies, compared with parley-files-sftp

For SFTP, use parley-files-sftp (type id `sftp`) unless you need VFS itself: To-Do 17.2 compared the two
(parley-files-sftp with its jsch provider, so both run on the same JSch) on mx1 against OpenSSH, with the results in
parley-files-compare's `docs/RESULTS.md` (2026-11-13). vfssftp stays available. Where it falls short:

1. **It can hang for ever on a server that accepts the connection and never answers.** VFS's connect timeout covers
   only its channels: it starts the SSH session with no timeout (item 19 below). parley-files-sftp gives up after its
   `connectTimeout` (30 s).
2. **No owner or group** (`getOwner()` and `getGroup()` have no name), **no links** (it can't make one or say where one
   leads), **no access or creation time**.
3. **Nothing atomic**: `createNewFile()` and `mkdir()` check, then act. parley-files-sftp's are atomic.
4. **Can't write at a position** (`getRandomAccessStream("rw")`), so one of the shared NIO tests
   (`fileChannelMapsPrivateAndReadWrite`) is skipped. parley-files-sftp with jsch can't either, but passes that test.
5. **Slower with small files**: creating 1,000 took 1.19 s against 0.59 s, deleting them 0.35 s against 0.30 s.
   Connecting and big transfers are the same (100 MB at about 125-145 MB/s for both).
6. **Larger**: 9 jars, 2.9 MB (with JSch), against 6 jars, 1.2 MB; about 330 more classes loaded.

Both pass the shared suites equally where both can run a test; both recover from a dropped connection on the next
call, keep 8 threads on one connection right, and leave part of the file when an upload is cut
(parley-files-sftp's `safeOverwrite` setting avoids that).

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

### vfsftp's and vfsftps's deficiencies, compared with parley-files-ftp

For FTP and FTPS, use parley-files-ftp (type ids `ftp` and `ftps`) unless you need VFS itself: To-Do 17.2 compared
them on mx1 against Parley's FtpServer (FTPS as implicit TLS), with the results in parley-files-compare's
`docs/RESULTS.md` (2026-11-13, and the timeout rerun of 2026-11-14). vfsftp and vfsftps stay available. Where they
fall short:

1. **Much slower with small files and over TLS.** Over FTP, creating 1,000 small files took 46.9 s against 0.73 s
   (about 64 times). Over FTPS, a 1 MB upload took 0.152 s against 0.015 s, 100 small files 14.6 s against 0.71 s,
   and 8 threads 17.6 s against 5.7 s. Over explicit TLS (vfsftp with `secure`) the same: 100 small files 14.6 s
   against 1.04 s, a 1 MB upload 0.194 s against 0.040 s. Big transfers are close (100 MB within 25 %).
2. **Can't set permission bits or times** (VFS's ftp has no SITE CHMOD or MFMT), so three of the shared tests that set
   permissions fail: 22 passed against 26 for parley-files-ftp. A directory's time is the listing's.
3. **Can't write at a position** (`getRandomAccessStream("rw")`), so `fileChannelMapsPrivateAndReadWrite` is skipped.
4. **Nothing atomic**: `mkdir()` checks, then acts. parley-files-ftp's is atomic.
5. **vfsftp gives up on a server that never answers after twice its timeout** (60 s with `timeout` 30s); vfsftps
   after 30 s. parley-files-ftp gives up after 30 s for both since 2026-11-14 (branch `ftp-connect-timeout`).
6. **Larger**: 9 jars, 2.5 MB, against 7 jars, 1.1 MB; about 460 more classes loaded over FTP (1,231 against 773).

Where VFS is ahead: deleting 1,000 small files took 0.25-0.27 s against 0.41 s, and a file another connection adds
shows at once (parley-files-ftp caches a folder's listing for its `attributeCacheTtl`, 2 s by default). Both fail
`fileChannelForceSendsWhatIsHeld` (an FTP upload is stored when it ends), fail at once when the connection is cut,
recover on the next call, keep 8 threads on one connection right, and leave no file when an upload is cut. VFS's
ftps used directly accepts a certificate it wasn't told to trust (item 14 below); vfsftp and vfsftps refuse one, as
parley-files-ftp does.

## Using vfsfile, vfstmp, vfsram and vfsres

None of them needs a library.

1. **`vfsfile`** is the local disk through VFS. Its one setting, `root`, is the local folder it serves as "/" (by
   default the whole disk: `/`, or on Windows the drive of the working directory); nothing above the root can be
   reached. `vfsfile:///tmp/a.txt` is the disk's `/tmp/a.txt`.
2. **`vfsram`** is a file system in memory and **`vfstmp`** one in a temporary folder VFS makes in `java.io.tmpdir`.
   Their one setting is `name` (default `default`): factories of the same type with the same name, and their copies,
   share one file system, which is gone (vfstmp's folder deleted) when the last of them disconnects.
   `vfsram:///a.txt?name=cache`.
3. **`vfsres`** reads the class path, read only, with no settings: `/a/b.txt` (`vfsres:/a/b.txt`) is the resource
   `a/b.txt`. "/" lists nothing, since nothing can list the class path, and a folder lists the first class path entry
   (jar or folder) that has it.

```java
FileSource scratch = FileSourceFactory.getFileSource("vfsram:///work/a.txt?name=cache");
FileSource props = FileSourceFactory.getFileSource("vfsres:/META-INF/MANIFEST.MF");
```

vfsfile and vfstmp read and set every permission bit, each alone, where the disk has POSIX bits; vfsram has none.

## Using the archive schemes

`vfszip`, `vfsjar`, `vfstar`, `vfstgz` and `vfstbz2` read an archive, read only. Their one setting, `archive`, is a
local file (`/tmp/a.zip`, `C:\a.zip`) or any VFS URI (`file:///tmp/a.zip`, `sftp://host/a.zip`, or an archive in an
archive: `zip:file:///outer.zip!/inner.zip`); paths are inside the archive. Their URL is VFS's with `vfs` in front.

```java
FileSource b = FileSourceFactory.getFileSource("vfszip:file:///tmp/a.zip!/dir/b.txt");
FileSource inTgz = FileSourceFactory.getFileSource("vfstgz:file:///tmp/a.tgz!/dir/b.txt");
```

`vfsgz` and `vfsbz2` hold one file, named after the compressed file without its extension (`a.txt.gz` holds
`/a.txt`). It can be read and written; writing replaces the whole compressed file, and makes it if it isn't there yet.
Its length is counted by reading it through (VFS doesn't know it), once per change.

## Using vfshttp and vfshttps

A web site, read only. Settings: `host`, `port` (80, or 443 for vfshttps), `user` and `password` (sent when the server
asks for a login) and `timeout` (30s). Add `org.apache.httpcomponents.client5:httpclient5`.

```java
FileSource page = FileSourceFactory.getFileSource("vfshttps://example.com/pub/notes.txt");
FileSource[] files = FileSourceFactory.getFileSource("vfshttps://example.com/pub/").listFiles();
```

A folder's files are the links on the server's listing page (Apache's, nginx's and the like): a folder whose listing
the server won't show lists nothing, but its files can still be read by name. Reading at a position uses the server's
ranges where it has them. HTTPS certificates are checked as Parley checks them (the chain by the application's or the
JVM's trust managers, and the host), also after a redirect to HTTPS: VFS by itself accepts any certificate.

## Using vfswebdav and vfswebdavs

A WebDAV server, read and write. The settings are vfshttp's (`host`, `port` 80 or 443, `user`, `password`, `timeout`
30s). Add `org.apache.commons:commons-vfs2-jackrabbit2`.

```java
FileSource doc = FileSourceFactory.getFileSource("vfswebdavs://tony@dav.example.com/dav/notes.txt");
```

It can't append, and an upload is kept in memory until its stream is closed (VFS sends it then); each upload is then
checked to be on the server at the size written. Certificates are checked as for vfshttps. Folders and files are told
apart, and folders listed, with this module's own PROPFIND: VFS's fails on servers that indent their answers (Tomcat).
Parley's own WebDAV FileSource is planned (To-Do 17.57).

## Using vfshdfs

Hadoop's HDFS, read and write. Settings: the name node's `host` and `port` (8020), and optionally `config`, a local
Hadoop configuration file (`hdfs-site.xml`, `core-site.xml`) for what the cluster needs beyond its address. The user
is Hadoop's (the local user, or HADOOP_USER_NAME). Add `org.apache.commons:commons-vfs2-hdfs` **and** Hadoop's client
(`org.apache.hadoop:hadoop-client`): VFS's pom drops every dependency of Hadoop's, so its HDFS provider can't run alone.

```java
FileSource f = FileSourceFactory.getFileSource("vfshdfs://namenode:8020/user/tony/a.txt");
```

Its tests run only against a cluster you give: `mvn test -Dparley.test.hdfs=hdfs://namenode:8020/a/writable/dir`.

## Workarounds and skipped tests

Everything parley-files-vfs does differently from VFS left to itself, and every test that doesn't run, with where it is
and what tests it, as a checklist for testing. Item numbers in brackets are those of "What VFS does" below; DESIGN
sections are in [docs/DESIGN.md](docs/DESIGN.md). Test names are classes in `src/test` and their methods
(`AbstractVfs*Test` methods run for every scheme with a test server: vfssftp, vfsftp, vfsfile, vfstmp, vfsram).

### A. Every scheme (`VfsFileSource`, `VfsFileSourceFactory`)

| # | VFS left to itself | What parley-files-vfs does | Tested by |
|---|---|---|---|
| A1 | One manager for the whole JVM (`VFS.getManager()`), which closing would close for everyone [6] | Each factory makes and closes its own manager | `AbstractVfsTrapsTest.eachFactoryHasAVfsManagerOfItsOwn` (two factories, different managers; disconnecting one leaves the other's files and an open stream working) |
| A2 | Moving onto a file that exists deletes it first, a folder with everything in it [3] | `renameTo` returns false if the destination exists; `renameTo(dest, true)` replaces a file or empty folder | `AbstractVfsTrapsTest`: `renameOntoAnExistingFileReturnsFalseAndKeepsBoth`, `renameOntoAnExistingDirectoryLeavesItAlone`, `renameReplacingAFileReplacesIt`, `renameIntoAMissingDirectoryReturnsFalse` |
| A3 | Create-folder makes every missing parent and does nothing if the folder is there [4] | `mkdir` is one level and false if it's there; `mkdirs` false if it's there | `mkdirIsOneLevelAndFalseIfThere` |
| A4 | Create-file makes missing parents and does nothing if the file is there [4] | `createNewFile` false if it's there (never truncates), IOException without its directory. **Not atomic** | `createNewFileOnAnExistingFileReturnsFalseAndKeepsIt`, `capabilitiesClaimNothingAtomic` |
| A5 | An output stream makes missing parents | FileNotFoundException for a missing parent or a directory, as `FileOutputStream` | `writingIntoAMissingDirectoryFails` |
| A6 | Deleting a non-empty folder, listing a file | `delete` false for a non-empty folder; `listFiles` null for a file, as `java.io.File` | `deleteIsLikeJavaIoFile`, `listingIsNullForAFileAndNamesTheChildren` |
| A7 | No canonical path (links can't be followed) | The canonical path is lexical ("." and ".." resolved), as in parley-files-ftp | `pathsAreLexical` |
| A8 | Capabilities say nothing of atomicity | `getCapabilities()` claims nothing atomic or durable; lock scope PROCESS | `capabilitiesClaimNothingAtomic`, `vfsLocksStayInThisJvm` |
| A9 | A `FileObject` from a closed manager is dead | A file resolves again after its factory reconnects | `aFileWorksAfterItsFactoryReconnects` (both factory tests) |
| A10 | Open streams belong to a file's `FileContent` per thread; closing it closes them all | Each stream is closed on its own; `FileContent.close()` is never called while one may be open | The shared suites (no test of its own) |
| A11 | Owner, group, link target, access and creation times unknown | A principal with no name; `getLinkedTo()` null; times 0 | `ownerGroupLinkAndOtherTimesAreUnknown` (a file and a folder; the setters return false) |
| A12 | `isHidden()` not answered by every provider | VFS's answer, or a name starting with "." | `aNameStartingWithADotIsHidden` (a file and a folder; a dot inside the name doesn't count) |
| A13 | A `vfs<scheme>:` URL has no `URLConnection` (parley-files' takes only `filesource:`) [11] | `VfsUrlConnection` opens it with `getFileSource(URL)` and disconnects that factory when its stream closes | `aVfsUrlOpensAsAJavaNetUrl` (both factory tests) |
| A14 | A write on a read-only file system (an archive) fails with VFS's message | An IOException saying the file system is read only | `VfsLocalFactoryTest.vfsresIsReadOnly`, `VfsArchiveTest.readOnly` (every archive type) |
| A15 | A file system that can't list (http) throws | An empty list (Q6); vfshttp lists from listing pages, and a folder whose page can't be read lists nothing | `VfsHttpTest.aFolderWithoutAListingListsNothing` |
| A16 | A nested URL (`vfszip:file:///a.zip!/dir`) reaches the factory whole | `createFileSource` takes a value starting with the root URI as a path; the archive is what comes before the last `!/` | `VfsArchiveTest.aVfsUrlOpens` (zip, tgz) |
| A17 | A scheme whose optional library is missing: sftp is dropped (connect says the URI is "a relative path"), ftp throws NoClassDefFoundError, and the manager made for that connect stays open | connect checks for the library's class first (`libraryClass()`) and throws an IOException naming the jar to add, e.g. "vfssftp needs JSch (com.github.mwiede:jsch) on the class path"; a connect that fails in any way, Errors included, closes its manager | `withoutJSch`, `withoutCommonsNet` (the message, twice, and still disconnected); the manager closed on an Error: not on its own |

| A18 | A thread-safe copy (a new manager) of a ram or tmp factory would see an empty file system | vfsram and vfstmp share one manager per type and `name`, counted, closed by the last factory to disconnect (DESIGN 14.2) | `VfsLocalFactoryTest`: `ramAndTmpAreSharedByName`, `ramAndTmpAreGoneWhenTheLastDisconnects`; `AbstractVfsTrapsTest.eachFactoryHasAVfsManagerOfItsOwn` (the same manager for one name) |
| A19 | A path resolves from the file system's root (for `file`, the whole disk) | A path resolves under the factory's root, so vfsfile reaches nothing above its `root` | `vfsfilePathsAreFromItsRootAndCantLeaveIt` |

### B. vfssftp (`VfsSftpFileSourceFactory`)

| # | VFS left to itself | What vfssftp does | Tested by |
|---|---|---|---|
| B1 | "/" is the user's home directory (`userDirIsRoot` true) [5] | Paths are the server's | Not distinguishable here: the test server's "/" is the user's home |
| B2 | A password login tries `~/.ssh/id_rsa` first | A password login gives VFS no keys; `preferredAuthentications` is `password,keyboard-interactive`, or `publickey` for a key | Every test (they log in with a password) |
| B3 | Host keys: VFS's own defaults | `knownHosts` (default `~/.ssh/known_hosts`) and `strictHostKeyChecking` (default yes), as parley-files-sftp (Q5) | `VfsSftpFactoryTest`: `anUnknownHostKeyIsRefused`, `aKnownHostKeyIsAccepted` |
| B4 | No owner, group or other bits in VFS's API [9] | The nine bits read from VFS's sftp provider by reflection (protected `getPermissions(false)`); false bits, logged once, if a VFS version lacks it | `VfsSftpPermissionBitsTest.bitsAreTheServers` |
| B5 | A permission change sends the whole stat back (size, owner, group, times, mode) [18] | A chmod with only the mode, from the server's mode read just before, special bits kept, on VFS's channel reached by reflection; VFS's setters if that fails (DESIGN 10.5) | `aFileItsOwnerCantReadCanBeChanged`, `aChangeMadeElsewhereIsKept`; the special bits can't be tested on MINA (Java can't hold them) |
| B6 | The group's and other users' bits can't be set alone | Each set alone by that chmod (DESIGN 10.6) | `groupAndOtherBitsAlone`, `TestVfsSftp.testPermissions`, `VfsSftpNioProviderTests`: `testFileAttributes`, `testCreateDir` |
| B7 | `knownHosts` and key files must be `java.io.File` | `knownHosts` is a local file (as in parley-files-sftp); a key is given to VFS as bytes | `aKnownHostKeyIsAccepted`; `VfsSftpLoginTest`: `aKeyFileLogsIn` (OpenSSH), `aPemKeyFileLogsIn`, `aPastedKeyLogsIn`, `aMissingKeyFileSaysWhichFile` |
| B8 | JSch needed on the class path to load anything | JSch is used from nested classes only, so the registry loads the factory without it | `VfsOptionalLibrariesTest.withoutJSch` (a class loader without JSch: all three factories register and show their settings; vfsftp works) |
| B9 | A key login also tries the password, and with no key given, `~/.ssh/id_rsa` | A key or agent login tries only keys (`preferredAuthentications` `publickey`, no identities of VFS's own) | `aKeyTheServerDoesntKnowIsRefusedAndNoPasswordIsTried`; `theAgentLogsIn` (with a wrong password set) |
| B10 | No ssh-agent login of its own (VFS takes a JSch `IdentityRepository` through `setIdentityRepositoryFactory`) | `auth` = `Agent`: JSch's `AgentIdentityRepository` on the running agent, found as parley-files-sftp's jsch provider finds it; an error naming SSH_AUTH_SOCK if there is none | `theAgentLogsIn`, `withNoAgentRunningAgentLoginSaysSo`, `agentIsAChoiceAndRoundTrips` |

### C. vfsftp and vfsftps (`VfsFtpFileSourceFactory`, `VfsFtpsFileSourceFactory`, `VfsTrustManager`)

| # | VFS left to itself | What vfsftp and vfsftps do | Tested by |
|---|---|---|---|
| C1 | Active mode [12] | Passive | `VfsFtpFactoryTest.vfsOptionsAreParleyLike` |
| C2 | "/" is the login directory [12] | Paths are the server's | `vfsOptionsAreParleyLike` (not distinguishable here: the test server's "/" is the login directory) |
| C3 | Times from the listing, to the minute at best | MDTM for files, to the second | `VfsFtpTrapsTest.timesCantBeSetButAreReadToTheSecond` |
| C4 | With MDTM on, a directory's time fails (servers answer MDTM for files) [15] | A directory's time from its listing entry, read by reflection | `aDirectoryHasATime` |
| C5 | File names in ISO-8859-1 | UTF-8, and OPTS UTF8 where the server lists it | `vfsOptionsAreParleyLike` |
| C6 | A transfer ended early (426) is a failed close [16] | 426 and 550 after an early close are fine | `AbstractVfsRandomAccessTest.headAndTail` |
| C7 | TLS: no PROT, so data goes in the clear [13] | PBSZ 0, PROT P | `VfsFtpTlsTest.tlsOptions`, and its listing, reading and writing over TLS |
| C8 | TLS: any in-date certificate accepted, for any host [14] | `VfsTrustManager`: the chain by `SecureBaseObject`'s trust managers or the JVM's, and the host name (RFC 6125) | `aCertificateTheJvmDoesntTrustIsRefused`, `aCertificateForAnotherHostIsRefused`, `VfsTrustManagerTest` |
| C9 | VFS's ftps defaults to explicit TLS | vfsftp with `secure` is explicit, vfsftps implicit, as parley-files-ftp | `vfsftpWithSecureIsExplicitTls`, `vfsftpsIsImplicitTls` |
| C10 | `vfs` + VFS's URI for explicit TLS would be `vfsftps:` (implicit) | `getURL()` is `vfsftp://...?secure=true` | `explicitTlsUrlsSaySecure` |
| C11 | No owner, group or other bits in VFS's API [17] | The nine bits from the listing entry (private `ftpFile`) by reflection | `VfsFtpPermissionBitsTest` (skipped as root: D6) |
| C12 | No ACCT, no writing at a position | `account` and `storeAtOffset` aren't offered; a saved connection with them still opens | `settingsAreParleyFilesFtps` |
| C13 | Can't set a permission bit or a time | The setters return false | `TestVfsFtp.testPermissions`, `timesCantBeSetButAreReadToTheSecond` |
| C14 | commons-net needed on the class path to load anything | commons-net is used from a nested class only | `VfsOptionalLibrariesTest.withoutCommonsNet` (without commons-net: all three register; vfssftp works) |

### E. vfsfile, vfstmp, vfsram, vfsres (`VfsFileFileSourceFactory`, `VfsNamedFileSourceFactory`, `VfsResFileSourceFactory`)

| # | VFS left to itself | What parley-files-vfs does | Tested by |
|---|---|---|---|
| E1 | `file` is the whole disk | A `root` folder (default the whole disk); a root that isn't a folder fails the connect | `vfsfileRootMustBeAFolder`, `vfsfileWithoutARootServesTheWholeDisk` |
| E2 | Only what the connected user can do, no owner, group or other bits [9] | vfsfile and vfstmp read and set the nine bits with java.nio, each alone (`LocalBits`; a tmp file's path by reflection on `LocalFile.getLocalFile()`, false bits, logged once, if that fails) | `VfsFilePermissionBitsTest`, `TestVfsFile` and `TestVfsTmp`'s `testPermissions`, their NIO suites' `testFileAttributes` and `testCreateDir` |
| E3 | `ram` has no permission bits | They read false and every setter returns false | `TestVfsRam.testPermissions` |
| E4 | `res:/` is refused; `res:a/b` becomes the jar or folder holding it [20] | "/" is an empty folder of the factory's own; `/a/b` is the resource `a/b`; a missing one doesn't exist; read only, whatever its jar or folder could do | `vfsresReadsTheClassPath`, `vfsresIsReadOnly`, `vfsresUrls` |

### F. The archives (`VfsArchiveFileSourceFactory`, `VfsCompressedFileSourceFactory`)

| # | VFS left to itself | What parley-files-vfs does | Tested by |
|---|---|---|---|
| F1 | tar, tgz, tbz2 and bz2 are dropped without commons-compress (an unknown scheme) | connect says which jar to add (`libraryClass()`) | `VfsOptionalLibrariesTest.withoutCommonsCompress` (zip and gz still work) |
| F2 | The archive is a URI | A local path or a VFS URI; a path is made a `file:` URI, so both are one file system; a nested server's password stays out of URLs | `archiveIsALocalPathOrAVfsUri`, `anArchiveInsideAnArchive`, `aPasswordInTheArchiveUriStaysOutOfUrls` |
| F3 | gz and bz2: the one file exists, and is listed, before the compressed file does [21] | It exists only when the compressed file does; writing it makes the compressed file | `VfsCompressedTest.writingMakesAMissingCompressedFile` |
| F4 | gz and bz2: any name is written over the compressed file [21] | Only the one file can be written | `onlyItsOneFileCanBeWritten` |
| F5 | gz and bz2: the file's length is -1 [21] | Counted by reading it through, kept while the compressed file is unchanged | `readsAndCountsItsOneFile`, `writingReplacesTheCompressedFile` |

### G. vfshttp and vfshttps (`VfsHttpFileSourceFactory`, `VfsHttpsFileSourceFactory`, `VfsHttpClient`)

| # | VFS left to itself | What vfshttp and vfshttps do | Tested by |
|---|---|---|---|
| G1 | Every HTTPS certificate trusted (httpclient5's TrustAllStrategy), whatever the options [22] | Their own provider: the chain by `VfsTrustManager`, the host by httpclient5's verifier, for `http` and `https` (redirects too) | `VfsHttpsTest`: `aCertificateTheJvmDoesntTrustIsRefused`, `aTrustedCertificateForAnotherHostIsRefused`, `aRedirectToHttpsIsChecked`, `vfsAloneTrustsAnything` |
| G2 | Every URL that answers is a file; no listing [23] | A folder is a path whose "/" form is an HTML page (or a refused listing); its files are its page's links | `foldersFromListingPages`, `listingLinks`, `aFolderWithoutAListingListsNothing` |
| G3 | A password given through an authenticator is cleared before httpclient5 uses it [24] | The client gets a copy | `aLogin` |
| G4 | A root URI with a user (`http://tony@host/`) fails every request [24] | No user in the root URI; it goes through the authenticator; the factory's URL keeps it | `aLogin`, `urls` |
| G5 | httpclient5 needed on the class path | Used from `VfsHttpClient` only, loaded after connect checks for it | `VfsOptionalLibrariesTest.withoutHttpClient5` |
| G6 | A refused login: "Could not determine the type of file" | "... refused the login (401 Unauthorized)" | `aLogin` |

### H. vfswebdav and vfswebdavs (`VfsWebdavFileSourceFactory`, `VfsWebdavsFileSourceFactory`, `VfsWebdavClient`)

| # | VFS left to itself | What vfswebdav and vfswebdavs do | Tested by |
|---|---|---|---|
| H1 | Every HTTPS certificate trusted [22] | Their own provider: the chain by `VfsTrustManager`, the host by httpclient's verifier | `VfsWebdavsTest`: `aCertificateTheJvmDoesntTrustIsRefused`, `aTrustedCertificateForAnotherHostIsRefused` |
| H2 | A server that indents its PROPFIND answer (Tomcat): no file's type can be told, nothing listed [25] | Their own PROPFIND for the type and the listing, reading a resourcetype however it is laid out | `VfsWebdavTest`: `foldersAndFilesOnAServerThatIndents`, `vfsAloneCantReadAServerThatIndents`, `resourceTypes`; the shared suites on Tomcat |
| H3 | Members the server hasn't got are listed (no 200 propstat) [25] | Only the folder's own members with a 200 propstat | `membersTheServerHasntGotAreLeftOut` |
| H4 | An upload the server stores short or drops goes unnoticed | The close checks the file's size on the server | `anUploadTheServerCutsShortFails` |
| H5 | commons-vfs2-jackrabbit2 needed on the class path | Used from `VfsWebdavClient` only, loaded after connect checks for it | `VfsOptionalLibrariesTest.withoutJackrabbit2` |
| H6 | No append | Refused, saying so (decided) | `VfsWebdavTrapsTest.appendIsRefused` |
| H7 | Reading from the end asks for a range there: 416, and VFS throws (every scheme with ranges) | An empty stream, without asking | `AbstractVfsRandomAccessTest.streamFromAPosition` (vfswebdav) |

### I. vfshdfs (`VfsHdfsFileSourceFactory`)

| # | VFS left to itself | What vfshdfs does | Tested by |
|---|---|---|---|
| I1 | `commons-vfs2-hdfs` alone can't run: VFS's pom drops Hadoop's dependencies [26] | connect checks for VFS's provider and Hadoop's `FileSystem` and names both libraries to add | `VfsHdfsFactoryTest.withoutTheLibraryConnectSaysWhatToAdd` |
| I2 | An unreachable name node: "Unknown message with code \"Unable to check existence \"" | The reason (`ConnectException` ...) | `withTheLibrary` (hdfs profile) |
| I3 | Hadoop at compile time for its configuration option | The configuration file is given to VFS by reflection | `withTheLibrary` (hdfs profile) |

### D. Tests that don't run, and why

With the test server each class starts: as an ordinary user 185 tests, 29 skipped, none failing (with or without an
ssh-agent: D10); as root 179.

| # | Test | Skipped for | Count |
|---|---|---|---|
| D1 | `AbstractLockTests`' cross-process cases (`VfsSftpLockTest`, `VfsFtpLockTest`) | The suite skips them: VFS's locks reach only this JVM (scope PROCESS) | 11 each |
| D2 | `fileChannelMapsPrivateAndReadWrite` (the vfssftp and vfsftp NIO tests) | The suite skips it: it writes at a position, which no remote VFS scheme can (vfsfile, vfstmp and vfsram run it) | 1 each |
| D3 | `VfsSftpNioProviderTests.transferToStopsWhenTheTargetTakesNothing` | JSch drops a stream whose reading thread has ended, and the test reads in one thread and closes in another [10] | 1 |
| D4 | `VfsFtpNioProviderTests`: `testFileAttributes`, `testCreateDir` | They set permission bits, which VFS's ftp can't (C13) | 2 |
| D5 | `VfsFtpNioProviderTests.fileChannelForceSendsWhatIsHeld` | An FTP upload is stored when it ends, as parley-files-ftp's NIO tests say too | 1 |
| D6 | `VfsFtpPermissionBitsTest` | As root only: Parley's FtpServer lists every file as writable by its owner when run as root | the class |
| D7 | `AbstractVfsPermissionBitsTest` (both schemes) | On Windows: POSIX bits | the class |
| D8 | `AbstractRandomAccessStreamTests` (vfssftp, vfsftp) | Not run for them: it writes at positions; `AbstractVfsRandomAccessTest` reads at positions instead, and checks that "rw" is refused. vfsfile, vfstmp and vfsram run it | not counted |
| D9 | `TestVfsFtp.testPermissions` | Replaced, not skipped: the shared test toggles bits; vfsftp's checks that every setter returns false and changes nothing (C13) | not counted |
| D11 | `VfsRamNioProviderTests`: `testFileAttributes`, `testCreateDir` | They set permission bits, which VFS's ram hasn't got (E3) | 2 |
| D12 | `VfsRamNioProviderTests.fileChannelForceSendsWhatIsHeld` | VFS's ram stores a file when its stream closes | 1 |
| D13 | `AbstractLockTests`' cross-process cases (`VfsFileLockTest`, `VfsTmpLockTest`, `VfsRamLockTest`) | As D1 | 11 each |
| D14 | vfswebdav: `TestVfsWebdav.testAppend`, `VfsWebdavTrapsTest.appendAddsToTheEnd`, and `VfsWebdavLockTest`'s `fileChannelLocksTheFile`, `fileChannelRules`, `closeRacingLockAndReleaseLeavesNothingHeld` (they open with APPEND) | vfswebdav refuses append (H6) | 5 |
| D15 | `VfsWebdavNioProviderTests`: `testFileAttributes`, `testCreateDir`, `fileChannelForceSendsWhatIsHeld` | WebDAV has no permission bits; an upload is sent when its stream closes | 3 |
| D16 | `AbstractLockTests`' cross-process cases (`VfsWebdavLockTest`) | As D1 | 11 |
| D17 | `TestVfsHdfs`; `VfsHdfsFactoryTest`: `aClusterUrlOpens`, `withTheLibrary` | No cluster given (`-Dparley.test.hdfs=hdfs://namenode:8020/dir`); `withTheLibrary` needs the hdfs profile | the class, 2 |
| D10 | `VfsSftpLoginTest`: `theAgentLogsIn`, or `withNoAgentRunningAgentLoginSaysSo` | One of the two, by whether an ssh-agent with a key is running (SSH_AUTH_SOCK); the second also on Windows | 1 |

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
    grows between VFS's stat and its setstat could be cut back. vfssftp sends a chmod with only the mode, read from the
    server just before, as parley-files-sftp does (docs/DESIGN.md, 10.5).
19. **VFS's sftp starts the SSH session with no timeout.** Its connect timeout (`setConnectTimeout`) is used only for
    its channels; JSch's `Session.connect()` is called without one, and only the separate session timeout
    (`setSessionTimeout`, unset by default) would end the wait. So against a server that accepts the connection and
    never answers, connecting waits for ever (To-Do 17.2: no answer in 120 s on mx1, nor in 400 s in a cloud check).
    vfssftp leaves it as VFS has it (decided 2026-11-13, documented rather than fixed).
20. **VFS's `res` isn't a file system.** `res:/` is refused, and `res:a/b.txt` turns into the jar entry or file that
    holds the resource (`jar:file:///lib/x.jar!/a/b.txt`), so a folder lists only the first class path entry that has
    it. VFS's `ram` and `tmp` file systems belong to a manager and go when it closes (`tmp`'s folder is deleted).
21. **VFS's gz and bz2 hold one file and get it wrong at the edges.** The file is named after the compressed file
    without its extension. VFS says it exists, and lists it, before the compressed file exists; it writes any name
    given (`gz:file:///a.txt.gz!/other.txt`) over the compressed file; and it gives the file's length as -1.
22. **VFS's http and https trust every certificate.** Its httpclient5 provider builds its TLS with `TrustAllStrategy`
    whatever its options say, even with a key store set; only its hostname verifier (on by default) checks anything.
23. **VFS's http has no folders.** Every URL that answers is a file, and listing isn't implemented.
24. **VFS's http can't log in through an authenticator, nor with a user in the URI.** It hands httpclient5 the
    authenticator's password array and then clears it, so the password sent is empty; and httpclient5 refuses a request
    URI with a user in it, which VFS makes from the root URI.
25. **VFS's WebDAV can't read an indented PROPFIND answer.** It casts `<D:resourcetype>`'s value to one XML node; a
    server that indents (Tomcat's WebdavServlet) puts whitespace around `<D:collection/>`, Jackrabbit then gives a list
    of nodes, and VFS fails with a ClassCastException: it can't tell what "/" is, nor list anything. Its listing also
    keeps members the server answered without a 200 propstat.
26. **VFS's HDFS provider can't run from its own pom.** VFS's project pom excludes every dependency of hadoop-common
    (`*:*`), so with `commons-vfs2-hdfs` alone Hadoop's configuration fails to load (no Woodstox). Hadoop's client must
    be added beside it.
