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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.test.FileSourceTestSupport;
import us.bringardner.parley.ftp.server.AnonymousUserRegistry;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.iam.permission.StandardPermissions;

/**
 * An FTP server for one test class: Parley's own FtpServer (as parley-files-ftp's tests use, so
 * To-Do 17.2 compares the two against the same server), on a free port, serving a new directory of
 * its own as "/" to anonymous FTP (user ftp) with read and write. Plain, or TLS from the first byte
 * (implicit, for vfsftps); a plain server also offers AUTH TLS when a key store is set
 * (parley.ftp.keyStoreName, as VfsFtpTlsTest does).
 */
final class VfsFtpTestServer implements VfsTestServer {

	static final String USER = "ftp";
	static final String PASSWORD = "foo@bar.com";
	static final String HOST = "127.0.0.1";

	private static final AtomicInteger COUNT = new AtomicInteger();

	private final Path home;
	private final boolean implicitTls;
	private FtpServer svr;

	VfsFtpTestServer(boolean implicitTls) throws IOException {
		this.implicitTls = implicitTls;
		home = Paths.get(FileSourceTestSupport.scratchDir("vfs-ftp-" + COUNT.incrementAndGet()));
		Files.createDirectories(home);
	}

	/** A started plain FTP server. */
	static VfsFtpTestServer started() throws IOException {
		return started(false);
	}

	/** A started server, implicit TLS or plain. */
	static VfsFtpTestServer started(boolean implicitTls) throws IOException {
		VfsFtpTestServer s = new VfsFtpTestServer(implicitTls);
		FileSourceTestSupport.startServer(s, 10_000);
		return s;
	}

	@Override
	public synchronized void start() throws IOException {
		FtpServer s = new FtpServer();
		s.setUserRegistry(new AnonymousUserRegistry(StandardPermissions.READ, StandardPermissions.WRITE));
		s.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(home.toString()));
		s.setPort(0);
		s.setSecure(implicitTls);
		s.setLogLevel(LogLevel.ERROR);
		try {
			s.start();
		} catch (Exception e) {
			throw new IOException("Can't start the FTP server", e);
		}
		svr = s;
	}

	@Override
	public synchronized void stop() throws IOException {
		FtpServer s = svr;
		svr = null;
		if( s != null ) {
			try {
				s.stop();
			} catch (Exception e) {
				throw new IOException("Can't stop the FTP server", e);
			}
		}
	}

	@Override
	public synchronized boolean isRunning() {
		return svr != null && svr.isRunning() && svr.getLocalPort() > 0;
	}

	@Override
	public String getName() {
		return "Parley FTP test server" + (implicitTls ? " (implicit TLS)" : "");
	}

	synchronized int port() {
		return svr.getLocalPort();
	}

	@Override
	public Path home() {
		return home;
	}

	/** Settings for anonymous FTP on this server. */
	Properties properties() {
		Properties p = new Properties();
		p.setProperty(VfsFtpFileSourceFactory.PROP_HOST, HOST);
		p.setProperty(VfsFtpFileSourceFactory.PROP_PORT, "" + port());
		p.setProperty(VfsFtpFileSourceFactory.PROP_USER, USER);
		p.setProperty(VfsFtpFileSourceFactory.PROP_PASSWORD, PASSWORD);
		p.setProperty(VfsFtpFileSourceFactory.PROP_TIMEOUT, "10s");
		return p;
	}

	/** A plain vfsftp factory connected to this server (a plain one); the caller disconnects it. */
	@Override
	public VfsFtpFileSourceFactory connect() throws IOException {
		VfsFtpFileSourceFactory f = new VfsFtpFileSourceFactory();
		f.setLogLevel(LogLevel.ERROR);
		f.setConnectionProperties(properties());
		assertTrue(f.connect(), "can't connect to " + HOST + ":" + port());
		return f;
	}
}
