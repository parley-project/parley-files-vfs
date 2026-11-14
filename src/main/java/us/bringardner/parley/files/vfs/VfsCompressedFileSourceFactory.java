/*
 * <PRE>
 *
 * Copyright Tony Bringarder 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 */
package us.bringardner.parley.files.vfs;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.vfs2.FileObject;

/**
 * The base of vfsgz and vfsbz2: an archive that holds one file, named after the compressed file
 * without its extension ({@code a.txt.gz} holds {@code /a.txt}), which can be read and written
 * (writing replaces the whole compressed file). The compressed file need not exist: writing its
 * file makes it (docs/DESIGN.md, 15.3).
 * <p>
 * Where VFS needs help: it says the one file exists before the compressed file does, it would write
 * any name over the compressed file, and it doesn't know the file's length, which is counted here
 * by reading it through once (kept while the compressed file is unchanged).
 */
public abstract class VfsCompressedFileSourceFactory extends VfsArchiveFileSourceFactory {

	private static final long serialVersionUID = 1L;

	/** The counted length and the compressed file it was counted on. */
	private static final class Counted {
		final long modified;
		final long size;
		final long length;

		Counted(long modified, long size, long length) {
			this.modified = modified;
			this.size = size;
			this.length = length;
		}
	}

	private transient volatile Counted counted;

	/** The compressed file itself, refreshed: whether it is there, and when it changed. */
	private FileObject compressedFile() throws IOException {
		FileObject r = root();
		FileObject ret = r.getFileSystem().getFileSystemManager().resolveFile(archiveUri());
		ret.refresh();
		return ret;
	}

	/** @return the name of the one file (VFS's: the compressed file's name without its extension) */
	public String fileName() throws IOException {
		FileObject[] kids = root().getChildren();
		if( kids.length != 1 ) {
			throw new IOException(getTypeId() + " found " + kids.length + " files in " + getURL());
		}
		return kids[0].getName().getBaseName();
	}

	private boolean isTheFile(FileObject fo) throws IOException {
		return fo.getName().getPath().equals("/" + fileName());
	}

	/** "/" always; the one file when the compressed file is there; nothing else. */
	@Override
	protected boolean exists(FileObject fo) throws IOException {
		if( fo.getName().getPath().equals("/") ) {
			return true;
		}
		return isTheFile(fo) && compressedFile().exists();
	}

	/** Only the one file can be written: VFS would write any name over the compressed file. */
	@Override
	protected void checkWrite(FileObject fo, String path) throws IOException {
		if( !isTheFile(fo) ) {
			throw new IOException(getTypeId() + " holds one file, /" + fileName() + ": " + path + " can't be written");
		}
	}

	/** VFS's length is unknown (-1): counted by reading the file through, once per change. */
	@Override
	protected long length(FileObject fo) throws IOException {
		long vfs = super.length(fo);
		if( vfs >= 0 ) {
			return vfs;
		}
		FileObject c = compressedFile();
		long modified = c.getContent().getLastModifiedTime();
		long size = c.getContent().getSize();
		Counted k = counted;
		if( k != null && k.modified == modified && k.size == size ) {
			return k.length;
		}
		long n = 0;
		byte[] buf = new byte[64 * 1024];
		try (InputStream in = fo.getContent().getInputStream()) {
			for (int got; (got = in.read(buf)) >= 0;) {
				n += got;
			}
		}
		counted = new Counted(modified, size, n);
		return n;
	}

	/** The compressed file need not exist: writing its one file makes it. */
	@Override
	protected void prepareRoot(FileObject r) {
		// VFS resolves the root of a compressed file that isn't there yet
	}
}
