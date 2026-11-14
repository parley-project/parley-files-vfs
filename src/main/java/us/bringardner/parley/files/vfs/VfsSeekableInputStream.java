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

import org.apache.commons.vfs2.RandomAccessContent;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.ISeekableInputStream;

/**
 * A read-only stream that can be moved, as a RandomAccessFile opened with "r", over VFS's random
 * access ({@code RandomAccessContent} in READ mode). The file's length is read once, when it is
 * opened. Not for use by several threads at once.
 */
final class VfsSeekableInputStream extends InputStream implements ISeekableInputStream {

	private final VfsFileSource file;
	private final RandomAccessContent rac;
	private final long length;
	private long pos;
	private boolean closed;

	VfsSeekableInputStream(VfsFileSource file, RandomAccessContent rac) throws IOException {
		this.file = file;
		this.rac = rac;
		try {
			this.length = rac.length();
		} catch (IOException | RuntimeException e) {
			rac.close();
			throw e;
		}
	}

	private void open() throws IOException {
		if( closed ) {
			throw new IOException("Stream closed");
		}
	}

	@Override
	public long length() throws IOException {
		open();
		return length;
	}

	/** Moves to POS; past the end is allowed, and reads there find the end of the file. */
	@Override
	public void seek(long newPos) throws IOException {
		open();
		if( newPos < 0 ) {
			throw new IOException("Negative seek offset");
		}
		pos = newPos;
	}

	@Override
	public long getFilePointer() throws IOException {
		open();
		return pos;
	}

	@Override
	public int read() throws IOException {
		open();
		if( pos >= length ) {
			return -1;
		}
		rac.seek(pos);
		int ret = rac.readUnsignedByte();
		pos++;
		return ret;
	}

	@Override
	public int read(byte[] data, int off, int len) throws IOException {
		open();
		java.util.Objects.checkFromIndexSize(off, len, data.length);
		if( len == 0 ) {
			return 0;
		}
		if( pos >= length ) {
			return -1;
		}
		int n = (int) Math.min(len, length - pos);
		rac.seek(pos);
		rac.readFully(data, off, n);
		pos += n;
		return n;
	}

	@Override
	public int read(byte[] data) throws IOException {
		return read(data, 0, data.length);
	}

	@Override
	public long skip(long n) throws IOException {
		open();
		if( n <= 0 ) {
			return 0;
		}
		long step = Math.min(n, Math.max(0, length - pos));
		pos += step;
		return step;
	}

	@Override
	public int available() throws IOException {
		open();
		return (int) Math.min(Integer.MAX_VALUE, Math.max(0, length - pos));
	}

	@Override
	public FileSource getFile() {
		return file;
	}

	/** This stream: reading it moves the file pointer, and closing it closes this. */
	@Override
	public InputStream getInputStream() {
		return this;
	}

	@Override
	public void close() throws IOException {
		if( !closed ) {
			closed = true;
			rac.close();
		}
	}
}
