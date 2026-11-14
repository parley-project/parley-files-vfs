# Changelog

## parley-files-vfs 2.0.0 (unreleased)

### Added (2026-11-13)

- **vfssftp sets the group's and other users' permission bits alone** (`setGroupReadable` and the like), through its
  chmod; they return false for schemes without one. The vfssftp tests that skipped these now run.

### Fixed (2026-11-13)

- **vfssftp's permission setters send only the mode**, as parley-files-sftp does: a chmod starting from the server's
  current mode, keeping the special bits, through `VfsFileSourceFactory.changeMode` (a new hook). VFS's own setter sent
  the file's whole stat back, which a server can refuse (`TestVfsSftp.testPermissions` failed as an ordinary user) and
  which could cut back a file that grew meanwhile. JSch is now an optional dependency.

### Added (2026-11-13: vfsftp and vfsftps)

- **`VfsFtpFileSourceFactory`** (type id `vfsftp`) and **`VfsFtpsFileSourceFactory`** (`vfsftps`): parley-files-ftp's
  settings and its two types (vfsftp with `secure` is explicit TLS, vfsftps implicit TLS); VFS's defaults changed to
  passive mode, the server's root, MDTM times for files, UTF-8 names and early-closed transfers accepted; with TLS,
  PROT P and the server's certificate checked (`VfsTrustManager`: the chain by the application's or the JVM's trust
  managers, and the host name), which VFS by itself doesn't do. Permission bits and a directory's time read from VFS's
  listing entry by reflection. commons-net is an optional dependency.
- **`vfsftp:` and `vfsftps:` URLs**.
- **`VfsFileSourceFactory.lastModified(FileObject)`**: a hook for a scheme that needs help with times.
- **Tests** against Parley's FtpServer: the shared suites, traps, random access, permission bits, factory, TLS and the
  host name rules. The tests every scheme shares are now abstract bases (`AbstractVfsTrapsTest` and the like) that
  vfssftp's tests extend too.

### Added (2026-11-13: the shared base and vfssftp)

- **`VfsFileSourceFactory`**, the shared base: a VFS manager of its own per factory (never `VFS.getManager()`),
  connect / disconnect, paths, current directory, `isSameFileSystem` by root URI, `createThreadSafeCopy`, URLs, and
  VFS's logging through the factory (`VfsLog`). The password reaches VFS through `VfsAuthenticator`, resolving a
  keychain reference when VFS asks.
- **`VfsFileSource`**: the FileSource over a VFS `FileObject`, with VFS's traps checked first (rename onto an
  existing file returns false instead of deleting it; `mkdir`, `createNewFile` and output streams don't make missing
  parents). Not atomic: `getCapabilities()` says so. Random access reading (`VfsRandomAccessIoController`,
  `VfsSeekableInputStream`) where the file system has it.
- **`VfsSftpFileSourceFactory`** (type id `vfssftp`): parley-files-sftp's settings and host key checking; paths are
  the server's; the nine permission bits read from VFS's sftp provider by reflection.
- **`vfssftp:` URLs** (`us.bringardner.parley.files.vfssftp.Handler`, `VfsUrlConnection`).
- **Tests** against an Apache MINA SFTP server each test class starts: the shared suites (`AbstractTestClass`,
  `AbstractNioProviderTests`, `AbstractLockTests`) and tests of VFS's traps, permission bits, random access reading,
  URLs, settings and host keys (79 tests, 15 skipped: 11 cross-process lock cases no PROCESS-scope file system runs,
  and 4 NIO cases VFS can't do, each saying why).

### Added (2026-11-13: new module, To-Do 17.1)

- **The project**: a Maven project modelled on parley-files-ftp, depending on parley-files and Apache Commons VFS
  2.10.0, with [docs/DESIGN.md](docs/DESIGN.md) mapping `FileSourceFactory` and `FileSource` onto VFS. No code yet.
