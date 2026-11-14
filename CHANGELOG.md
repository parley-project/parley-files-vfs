# Changelog

## parley-files-vfs 2.0.0 (unreleased)

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
