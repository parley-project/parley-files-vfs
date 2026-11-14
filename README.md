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

Every permission bit can be set, each alone (a chmod with only the mode, as parley-files-sftp sends). What it can't
do, from VFS: write at a position in a file (`getRandomAccessStream("rw")` is refused; `"r"` works), make links, or say
who owns a file. `createNewFile()` and `mkdir()` are **not atomic**
(`getCapabilities()` says so). Logging in through an ssh-agent isn't supported yet.

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
   and 8 threads 17.6 s against 5.7 s. Big transfers are close (100 MB within 25 %).
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

## Workarounds and skipped tests

Everything parley-files-vfs does differently from VFS left to itself, and every test that doesn't run, with where it is
and what tests it, as a checklist for testing. Item numbers in brackets are those of "What VFS does" below; DESIGN
sections are in [docs/DESIGN.md](docs/DESIGN.md). Test names are classes in `src/test` and their methods
(`AbstractVfs*Test` methods run for both vfssftp and vfsftp).

### A. Every scheme (`VfsFileSource`, `VfsFileSourceFactory`)

| # | VFS left to itself | What parley-files-vfs does | Tested by |
|---|---|---|---|
| A1 | One manager for the whole JVM (`VFS.getManager()`), which closing would close for everyone [6] | Each factory makes and closes its own manager | Not on its own (every factory test disconnects its own) |
| A2 | Moving onto a file that exists deletes it first, a folder with everything in it [3] | `renameTo` returns false if the destination exists; `renameTo(dest, true)` replaces a file or empty folder | `AbstractVfsTrapsTest`: `renameOntoAnExistingFileReturnsFalseAndKeepsBoth`, `renameOntoAnExistingDirectoryLeavesItAlone`, `renameReplacingAFileReplacesIt`, `renameIntoAMissingDirectoryReturnsFalse` |
| A3 | Create-folder makes every missing parent and does nothing if the folder is there [4] | `mkdir` is one level and false if it's there; `mkdirs` false if it's there | `mkdirIsOneLevelAndFalseIfThere` |
| A4 | Create-file makes missing parents and does nothing if the file is there [4] | `createNewFile` false if it's there (never truncates), IOException without its directory. **Not atomic** | `createNewFileOnAnExistingFileReturnsFalseAndKeepsIt`, `capabilitiesClaimNothingAtomic` |
| A5 | An output stream makes missing parents | FileNotFoundException for a missing parent or a directory, as `FileOutputStream` | `writingIntoAMissingDirectoryFails` |
| A6 | Deleting a non-empty folder, listing a file | `delete` false for a non-empty folder; `listFiles` null for a file, as `java.io.File` | `deleteIsLikeJavaIoFile`, `listingIsNullForAFileAndNamesTheChildren` |
| A7 | No canonical path (links can't be followed) | The canonical path is lexical ("." and ".." resolved), as in parley-files-ftp | `pathsAreLexical` |
| A8 | Capabilities say nothing of atomicity | `getCapabilities()` claims nothing atomic or durable; lock scope PROCESS | `capabilitiesClaimNothingAtomic`, `vfsLocksStayInThisJvm` |
| A9 | A `FileObject` from a closed manager is dead | A file resolves again after its factory reconnects | `aFileWorksAfterItsFactoryReconnects` (both factory tests) |
| A10 | Open streams belong to a file's `FileContent` per thread; closing it closes them all | Each stream is closed on its own; `FileContent.close()` is never called while one may be open | The shared suites (no test of its own) |
| A11 | Owner, group, link target, access and creation times unknown | A principal with no name; `getLinkedTo()` null; times 0 | Not on their own |
| A12 | `isHidden()` not answered by every provider | VFS's answer, or a name starting with "." | Not on its own |
| A13 | A `vfs<scheme>:` URL has no `URLConnection` (parley-files' takes only `filesource:`) [11] | `VfsUrlConnection` opens it with `getFileSource(URL)` and disconnects that factory when its stream closes | `aVfsUrlOpensAsAJavaNetUrl` (both factory tests) |
| A14 | A write on a read-only file system (an archive) fails with VFS's message | An IOException saying the file system is read only | Not yet: no archive scheme is built |
| A15 | A file system that can't list (http) throws | An empty list (Q6) | Not yet: no http scheme is built |
| A16 | A nested URL (`vfszip:file:///a.zip!/dir`) reaches the factory whole | `createFileSource` takes a value starting with the root URI as a path | Not yet: no archive scheme is built |

### B. vfssftp (`VfsSftpFileSourceFactory`)

| # | VFS left to itself | What vfssftp does | Tested by |
|---|---|---|---|
| B1 | "/" is the user's home directory (`userDirIsRoot` true) [5] | Paths are the server's | Not distinguishable here: the test server's "/" is the user's home |
| B2 | A password login tries `~/.ssh/id_rsa` first | A password login gives VFS no keys; `preferredAuthentications` is `password,keyboard-interactive`, or `publickey` for a key | Every test (they log in with a password) |
| B3 | Host keys: VFS's own defaults | `knownHosts` (default `~/.ssh/known_hosts`) and `strictHostKeyChecking` (default yes), as parley-files-sftp (Q5) | `VfsSftpFactoryTest`: `anUnknownHostKeyIsRefused`, `aKnownHostKeyIsAccepted` |
| B4 | No owner, group or other bits in VFS's API [9] | The nine bits read from VFS's sftp provider by reflection (protected `getPermissions(false)`); false bits, logged once, if a VFS version lacks it | `VfsSftpPermissionBitsTest.bitsAreTheServers` |
| B5 | A permission change sends the whole stat back (size, owner, group, times, mode) [18] | A chmod with only the mode, from the server's mode read just before, special bits kept, on VFS's channel reached by reflection; VFS's setters if that fails (DESIGN 10.5) | `aFileItsOwnerCantReadCanBeChanged`, `aChangeMadeElsewhereIsKept`; the special bits can't be tested on MINA (Java can't hold them) |
| B6 | The group's and other users' bits can't be set alone | Each set alone by that chmod (DESIGN 10.6) | `groupAndOtherBitsAlone`, `TestVfsSftp.testPermissions`, `VfsSftpNioProviderTests`: `testFileAttributes`, `testCreateDir` |
| B7 | `knownHosts` and key files must be `java.io.File` | `knownHosts` is a local file (as in parley-files-sftp); a key is given to VFS as bytes | `aKnownHostKeyIsAccepted` (the key: not tested) |
| B8 | JSch needed on the class path to load anything | JSch is used from a nested class only, so the registry loads the factory without it | Not tested |

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
| C14 | commons-net needed on the class path to load anything | commons-net is used from a nested class only | Not tested |

### D. Tests that don't run, and why

With the test server each class starts: as an ordinary user 169 tests, 28 skipped, none failing; as root 163.

| # | Test | Skipped for | Count |
|---|---|---|---|
| D1 | `AbstractLockTests`' cross-process cases (`VfsSftpLockTest`, `VfsFtpLockTest`) | The suite skips them: VFS's locks reach only this JVM (scope PROCESS) | 11 each |
| D2 | `fileChannelMapsPrivateAndReadWrite` (both NIO tests) | The suite skips it: it writes at a position, which no remote VFS scheme can | 1 each |
| D3 | `VfsSftpNioProviderTests.transferToStopsWhenTheTargetTakesNothing` | JSch drops a stream whose reading thread has ended, and the test reads in one thread and closes in another [10] | 1 |
| D4 | `VfsFtpNioProviderTests`: `testFileAttributes`, `testCreateDir` | They set permission bits, which VFS's ftp can't (C13) | 2 |
| D5 | `VfsFtpNioProviderTests.fileChannelForceSendsWhatIsHeld` | An FTP upload is stored when it ends, as parley-files-ftp's NIO tests say too | 1 |
| D6 | `VfsFtpPermissionBitsTest` | As root only: Parley's FtpServer lists every file as writable by its owner when run as root | the class |
| D7 | `AbstractVfsPermissionBitsTest` (both schemes) | On Windows: POSIX bits | the class |
| D8 | `AbstractRandomAccessStreamTests` | Not run at all: it writes at positions; `AbstractVfsRandomAccessTest` reads at positions instead, and checks that "rw" is refused | not counted |
| D9 | `TestVfsFtp.testPermissions` | Replaced, not skipped: the shared test toggles bits; vfsftp's checks that every setter returns false and changes nothing (C13) | not counted |

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
