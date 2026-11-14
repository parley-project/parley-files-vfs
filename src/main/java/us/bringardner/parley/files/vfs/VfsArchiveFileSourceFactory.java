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
import java.net.URL;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import org.apache.commons.vfs2.FileSystemOptions;

import us.bringardner.parley.files.ConnectionSetting;

/**
 * The base of the archive schemes (vfszip, vfsjar, vfstar, vfstgz, vfstbz2, and vfsgz and vfsbz2,
 * which hold one file): a factory is one archive, its {@value #PROP_ARCHIVE}, and its paths are
 * inside it ({@code /dir/a.txt}). The archive is a local path ({@code /tmp/a.zip},
 * {@code C:\a.zip}) or any VFS URI ({@code file:///tmp/a.zip}, {@code sftp://host/a.zip},
 * {@code zip:file:///outer.zip!/inner.zip}) (docs/DESIGN.md, 15.1).
 * <p>
 * The URL is VFS's with {@code vfs} in front: {@code vfszip:file:///tmp/a.zip!/dir}.
 */
public abstract class VfsArchiveFileSourceFactory extends VfsFileSourceFactory {

	private static final long serialVersionUID = 1L;

	/** Where the archive is: a local path or a VFS URI. */
	public static final String PROP_ARCHIVE = "archive";

	/** A URI's scheme: two characters at least, so a Windows drive ("C:") is a path. */
	private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]+:.*");
	/** user:password@ in a URI's authority. */
	private static final Pattern PASSWORD = Pattern.compile("(://[^:/@]*):[^@/]*@");

	private volatile String archive;

	/** @return the archive as given: a local path or a VFS URI; null if not set */
	public String getArchive() {
		return archive;
	}

	/** @param archive a local path or a VFS URI */
	public void setArchive(String archive) {
		this.archive = archive == null || archive.trim().isEmpty() ? null : archive.trim();
	}

	/**
	 * The archive as a VFS URI: as given if it is one, else the local path's {@code file:} URI.
	 * @return the URI
	 * @throws IOException if no archive is set
	 */
	public String archiveUri() throws IOException {
		String a = archive;
		if( a == null ) {
			throw new IOException("No archive for " + getTypeId());
		}
		if( URI_SCHEME.matcher(a).matches() ) {
			return a;
		}
		return Paths.get(a).toAbsolutePath().normalize().toUri().toString();
	}

	/** The archive's URI without a password a nested server's URI may carry, for URLs and titles. */
	String safeArchiveUri() throws IOException {
		return PASSWORD.matcher(archiveUri()).replaceAll("$1@");
	}

	/** {@code zip:file:///tmp/a.zip!/}. */
	@Override
	protected String rootUri() throws IOException {
		return vfsScheme() + ":" + archiveUri() + "!/";
	}

	@Override
	protected void configure(FileSystemOptions options) {
		// nothing to set
	}

	/** {@code vfszip:file:///tmp/a.zip!/}, without a nested server's password. */
	@Override
	public String getURL() {
		try {
			return getTypeId() + ":" + safeArchiveUri() + "!/";
		} catch (IOException e) {
			return getTypeId() + ":";
		}
	}

	@Override
	protected Map<String, String> urlSettings() {
		try {
			return archive == null ? Collections.emptyMap() : Collections.singletonMap(PROP_ARCHIVE, safeArchiveUri());
		} catch (IOException e) {
			return Collections.emptyMap();
		}
	}

	/**
	 * A {@code vfszip:file:///tmp/a.zip!/dir} URL: the archive is what comes before the last
	 * {@code !/} (or a final {@code !}), and its query may name settings.
	 */
	@Override
	public void setConnectionProperties(URL url) {
		String s = url.toString();
		int colon = s.indexOf(':');
		String rest = colon < 0 ? s : s.substring(colon + 1);
		int q = rest.indexOf('?');
		if( q >= 0 ) {
			rest = rest.substring(0, q);
		}
		int bang = rest.lastIndexOf("!/");
		if( bang < 0 && rest.endsWith("!") ) {
			bang = rest.length() - 1;
		}
		if( bang > 0 ) {
			setArchive(decode(rest.substring(0, bang)));
		}
		super.setConnectionProperties(url);
	}

	@Override
	public List<ConnectionSetting> getConnectionSettings() {
		return List.of(ConnectionSetting.localFile(PROP_ARCHIVE, "Archive").asRequired()
				.withDescription("A local file, or a VFS URI such as sftp://host/dir/a.zip or zip:file:///outer.zip!/inner.zip"));
	}

	@Override
	public Properties getConnectProperties() {
		Properties ret = new Properties();
		ret.setProperty(PROP_ARCHIVE, archive == null ? "" : archive);
		return ret;
	}

	@Override
	public void setConnectionProperties(Properties p) {
		if( p.containsKey(PROP_ARCHIVE) ) {
			setArchive(p.getProperty(PROP_ARCHIVE));
		}
	}
}
