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
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystem;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.NameScope;
import org.apache.commons.vfs2.UserAuthenticationData;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.commons.vfs2.provider.AbstractFileName;
import org.apache.commons.vfs2.provider.GenericFileName;
import org.apache.commons.vfs2.provider.UriParser;
import org.apache.commons.vfs2.provider.http4.Http4FileSystemConfigBuilder;
import org.apache.commons.vfs2.provider.webdav4.Webdav4FileNameParser;
import org.apache.commons.vfs2.provider.webdav4.Webdav4FileObject;
import org.apache.commons.vfs2.provider.webdav4.Webdav4FileProvider;
import org.apache.commons.vfs2.provider.webdav4.Webdav4FileSystem;
import org.apache.commons.vfs2.provider.webdav4.Webdav4FileSystemConfigBuilder;
import org.apache.commons.vfs2.provider.webdav4s.Webdav4sFileNameParser;
import org.apache.commons.vfs2.util.UserAuthenticatorUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.jackrabbit.webdav.DavConstants;
import org.apache.jackrabbit.webdav.DavException;
import org.apache.jackrabbit.webdav.MultiStatusResponse;
import org.apache.jackrabbit.webdav.client.methods.HttpPropfind;
import org.apache.jackrabbit.webdav.property.DavProperty;
import org.apache.jackrabbit.webdav.property.DavPropertyName;
import org.apache.jackrabbit.webdav.property.DavPropertyNameSet;
import org.apache.jackrabbit.webdav.property.DavPropertySet;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * vfswebdav's and vfswebdavs's use of VFS's WebDAV provider (webdav4: httpclient 4 and Jackrabbit),
 * in a class of its own: loaded only once connect has checked that it is there, never when the
 * registry loads the factories without it.
 * <p>
 * Each connection's manager gets providers of its own for {@code webdav4} and {@code webdav4s} in
 * place of VFS's (docs/DESIGN.md, 17):
 * <ol>
 * <li>The server's certificate chain is checked as Parley checks it ({@link VfsTrustManager}), and its
 * host by httpclient's hostname verifier: VFS's provider trusts every certificate.</li>
 * <li>A file's type and a folder's listing come from a PROPFIND of its own ({@link DavFile}), which
 * reads a {@code resourcetype} however the server lays it out: VFS's casts it to one XML node, and
 * fails on a server that indents its answer (Tomcat), so it can't tell a folder from a file; and
 * the listing keeps only the folder's own members that the server says are there.</li>
 * </ol>
 * VFS still reads, writes, creates, deletes and renames.
 */
final class VfsWebdavClient {

	private static final UserAuthenticationData.Type[] AUTHENTICATOR_TYPES = { UserAuthenticationData.USERNAME,
			UserAuthenticationData.PASSWORD };

	private VfsWebdavClient() {
	}

	/** Puts this module's webdav4 and webdav4s providers in the manager, in place of VFS's. */
	static void install(StandardFileSystemManager m) throws FileSystemException {
		for (String scheme : new String[] { "webdav4", "webdav4s" }) {
			m.removeProvider(scheme);
			m.addProvider(scheme, new Provider("webdav4s".equals(scheme)));
		}
	}

	/** VFS's WebDAV options, as the factory's settings have them. */
	static void configure(FileSystemOptions options, int timeoutMs) {
		Webdav4FileSystemConfigBuilder b = Webdav4FileSystemConfigBuilder.getInstance();
		Duration t = Duration.ofMillis(timeoutMs);
		b.setConnectionTimeout(options, t);
		b.setSoTimeout(options, t);
		b.setFollowRedirect(options, true);
		b.setHostnameVerificationEnabled(options, true);
	}

	/** VFS's WebDAV provider, with Parley's certificate check and {@link DavFile}s. */
	static final class Provider extends Webdav4FileProvider {

		Provider(boolean https) {
			setFileNameParser(https ? Webdav4sFileNameParser.getInstance() : Webdav4FileNameParser.getInstance());
		}

		/** Parley's check of the chain; the host is checked by httpclient's verifier, kept on by {@link #configure}. */
		@Override
		protected SSLContext createSSLContext(Http4FileSystemConfigBuilder builder, FileSystemOptions options)
				throws FileSystemException {
			try {
				SSLContext ret = SSLContext.getInstance("TLS");
				ret.init(null, new TrustManager[] { new VfsTrustManager(null) }, null);
				return ret;
			} catch (GeneralSecurityException e) {
				throw new FileSystemException("Can't check the server's certificate: " + e, e);
			}
		}

		/** As VFS's, with {@link DavSite} as the file system. */
		@Override
		protected FileSystem doCreateFileSystem(FileName name, FileSystemOptions options) throws FileSystemException {
			GenericFileName rootName = (GenericFileName) name;
			FileSystemOptions opts = options == null ? new FileSystemOptions() : options;
			UserAuthenticationData authData = null;
			HttpClient client;
			HttpClientContext context;
			try {
				Webdav4FileSystemConfigBuilder builder = Webdav4FileSystemConfigBuilder.getInstance();
				authData = UserAuthenticatorUtils.authenticate(opts, AUTHENTICATOR_TYPES);
				context = createHttpClientContext(builder, rootName, opts, authData);
				client = createHttpClient(builder, rootName, opts);
			} finally {
				UserAuthenticatorUtils.cleanup(authData);
			}
			return new DavSite(rootName, opts, client, context);
		}
	}

	/** VFS's WebDAV file system, whose files are {@link DavFile}s. */
	static final class DavSite extends Webdav4FileSystem {

		DavSite(FileName rootName, FileSystemOptions options, HttpClient client, HttpClientContext context) {
			super(rootName, options, client, context);
		}

		@Override
		protected FileObject createFile(AbstractFileName name) throws Exception {
			return new DavFile(name, this);
		}
	}

	/** VFS's WebDAV file, with its own PROPFIND for its type and its folder's members. */
	static final class DavFile extends Webdav4FileObject {

		DavFile(AbstractFileName name, DavSite fs) throws FileSystemException {
			super(name, fs);
		}

		/** The file's own resourcetype: a folder, a file, or (404) nothing. */
		@Override
		protected FileType doGetType() throws Exception {
			Map<String, Boolean> got = propfind(DavConstants.DEPTH_0);
			if( got == null ) {
				return FileType.IMAGINARY;
			}
			Boolean folder = got.get(path(getInternalURI()));
			if( folder == null ) {
				// the server answered for another name (a redirect, a different encoding): its one answer
				folder = got.size() == 1 ? got.values().iterator().next() : Boolean.FALSE;
			}
			return folder ? FileType.FOLDER : FileType.FILE;
		}

		@Override
		protected String[] doListChildren() throws Exception {
			return members().keySet().toArray(new String[0]);
		}

		/** The folder's members, each with its type, so no member is asked again. */
		@Override
		protected FileObject[] doListChildrenResolved() throws Exception {
			Map<String, Boolean> kids = members();
			List<FileObject> ret = new ArrayList<>(kids.size());
			for (Map.Entry<String, Boolean> e : kids.entrySet()) {
				FileName n = getFileSystem().getFileSystemManager().resolveName(getName(), UriParser.encode(e.getKey()),
						NameScope.CHILD);
				FileObject kid = getFileSystem().resolveFile(n);
				if( kid instanceof DavFile ) {
					((DavFile) kid).injectType(e.getValue() ? FileType.FOLDER : FileType.FILE);
				}
				ret.add(kid);
			}
			return ret.toArray(new FileObject[0]);
		}

		/**
		 * The members of this folder (depth 1), by name: only those directly in it that the server says
		 * are there (a 200 propstat); not the folder itself, nothing deeper, nothing elsewhere.
		 */
		private Map<String, Boolean> members() throws IOException {
			Map<String, Boolean> got = propfind(DavConstants.DEPTH_1);
			if( got == null ) {
				throw new FileSystemException("vfs.provider/list-children-not-folder.error", getName());
			}
			String me = path(getInternalURI());
			Map<String, Boolean> ret = new LinkedHashMap<>();
			for (Map.Entry<String, Boolean> e : got.entrySet()) {
				String p = e.getKey();
				if( p.equals(me) ) {
					continue;
				}
				int slash = p.lastIndexOf('/');
				String parent = slash <= 0 ? "/" : p.substring(0, slash);
				if( !parent.equals(me) ) {
					continue;
				}
				String name = p.substring(slash + 1);
				if( !name.isEmpty() ) {
					ret.put(name, e.getValue());
				}
			}
			return ret;
		}

		/**
		 * A PROPFIND of this file's resourcetype at DEPTH: each answered path (decoded, without a
		 * trailing "/") and whether it is a folder; null if the file isn't there (404).
		 */
		private Map<String, Boolean> propfind(int depth) throws IOException {
			URI uri = getInternalURI();
			DavPropertyNameSet names = new DavPropertyNameSet();
			names.add(DavPropertyName.RESOURCETYPE);
			HttpPropfind request = new HttpPropfind(uri, names, depth);
			HttpResponse r = executeHttpUriRequest(request);
			try {
				int code = r.getStatusLine().getStatusCode();
				if( code == 404 || code == 410 ) {
					return null;
				}
				if( !request.succeeded(r) ) {
					throw new FileSystemException("vfs.provider.webdav/propfind.error", getName(), Integer.valueOf(code));
				}
				Map<String, Boolean> ret = new LinkedHashMap<>();
				for (MultiStatusResponse each : request.getResponseBodyAsMultiStatus(r).getResponses()) {
					DavPropertySet props = each.getProperties(200);
					if( props == null || props.isEmpty() ) {
						// nothing found for it: not there (VFS lists it anyway)
						continue;
					}
					ret.put(path(uri.resolve(each.getHref())), isCollection(props.get(DavPropertyName.RESOURCETYPE)));
				}
				return ret;
			} catch (DavException e) {
				throw new FileSystemException(e.getMessage(), e);
			} finally {
				if( r instanceof CloseableHttpResponse ) {
					((CloseableHttpResponse) r).close();
				}
			}
		}

		/** A URI's path, decoded, without a trailing "/" ("/" for the root). */
		static String path(URI u) {
			String p = u.getPath();
			if( p == null || p.isEmpty() ) {
				return "/";
			}
			while( p.length() > 1 && p.endsWith("/") ) {
				p = p.substring(0, p.length() - 1);
			}
			return p;
		}
	}

	/**
	 * Whether a resourcetype names a collection: its value is one node, a list of nodes (with the
	 * whitespace an indenting server leaves between them), or none (a file).
	 */
	static boolean isCollection(DavProperty<?> resourceType) {
		if( resourceType == null ) {
			return false;
		}
		return isCollection(resourceType.getValue());
	}

	static boolean isCollection(Object value) {
		if( value instanceof Element ) {
			Element e = (Element) value;
			String local = e.getLocalName() == null ? e.getTagName() : e.getLocalName();
			return DavConstants.XML_COLLECTION.equals(local.substring(local.indexOf(':') + 1));
		}
		if( value instanceof Node ) {
			return false;
		}
		if( value instanceof Collection ) {
			for (Object each : (Collection<?>) value) {
				if( isCollection(each) ) {
					return true;
				}
			}
		}
		return false;
	}
}
