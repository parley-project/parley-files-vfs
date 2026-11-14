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

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * A connection for a {@code vfs<scheme>:} URL ({@code vfssftp://tony@host/dir/a.txt}). parley-files'
 * {@code FileSourceURLConnection} takes only {@code filesource:} URLs, so the handlers use this:
 * it opens the file through {@code FileSourceFactory.getFileSource(URL)}, which makes and connects a
 * factory for the URL, and disconnects that factory when the stream it gave is closed.
 */
public class VfsUrlConnection extends URLConnection {

	private FileSource target;

	public VfsUrlConnection(URL url) {
		super(url);
	}

	@Override
	public synchronized void connect() throws IOException {
		if( target == null ) {
			target = FileSourceFactory.getFileSource(getURL());
			connected = true;
		}
	}

	/** The file, connecting first if needed. */
	public synchronized FileSource getFileSource() throws IOException {
		connect();
		return target;
	}

	private void disconnect() {
		FileSource t;
		synchronized (this) {
			t = target;
			target = null;
			connected = false;
		}
		if( t != null ) {
			try {
				t.getFileSourceFactory().disConnect();
			} catch (IOException e) {
				// closing anyway
			}
		}
	}

	/** Closing it disconnects the URL's factory. */
	@Override
	public InputStream getInputStream() throws IOException {
		InputStream in;
		try {
			in = getFileSource().getInputStream();
		} catch (IOException | RuntimeException e) {
			disconnect();
			throw e;
		}
		return new FilterInputStream(in) {
			@Override
			public void close() throws IOException {
				try {
					super.close();
				} finally {
					disconnect();
				}
			}
		};
	}

	/** Closing it disconnects the URL's factory. */
	@Override
	public OutputStream getOutputStream() throws IOException {
		OutputStream out;
		try {
			out = getFileSource().getOutputStream();
		} catch (IOException | RuntimeException e) {
			disconnect();
			throw e;
		}
		return new FilterOutputStream(out) {
			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				out.write(b, off, len);
			}

			@Override
			public void close() throws IOException {
				try {
					super.close();
				} finally {
					disconnect();
				}
			}
		};
	}

	/** -1 if unknown. */
	@Override
	public long getContentLengthLong() {
		try {
			return getFileSource().length();
		} catch (IOException e) {
			return -1;
		}
	}

	/** 0 if unknown. */
	@Override
	public long getLastModified() {
		try {
			return getFileSource().lastModified();
		} catch (IOException e) {
			return 0;
		}
	}

	@Override
	public String getContentType() {
		try {
			return getFileSource().getContentType();
		} catch (IOException e) {
			return null;
		}
	}
}
