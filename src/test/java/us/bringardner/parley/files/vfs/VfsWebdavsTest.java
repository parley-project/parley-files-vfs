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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.core.SecureBaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * vfswebdavs and the certificate check VFS's WebDAV provider doesn't make (docs/DESIGN.md, 17.1),
 * on Tomcat over HTTPS: a trusted certificate for the host works (read and write); one the JVM
 * doesn't trust is refused, and so is a trusted one for another host.
 */
public class VfsWebdavsTest {

	private static final String PASSWORD = "test-only";

	private static TrustManager[] savedTrust;
	private static TrustManager[] trustTheTestCertificate;
	private static VfsWebdavTestServer server;

	private final List<FileSourceFactory> made = new ArrayList<>();

	@BeforeAll
	static void start() throws Exception {
		Path dir = Files.createTempDirectory("parley-files-vfs-webdavs");
		Path ks = VfsFtpTlsTest.keyStore(dir, "server", "CN=localhost", "dns:localhost");
		KeyStore trust = KeyStore.getInstance("PKCS12");
		trust.load(null, null);
		trust.setCertificateEntry("server", VfsFtpTlsTest.certificate(ks));
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(trust);
		trustTheTestCertificate = tmf.getTrustManagers();
		savedTrust = SecureBaseObject.getDefaultTrustManagers();
		server = VfsWebdavTestServer.started(ks, PASSWORD);
		Files.write(server.home().resolve("a.txt"), "secure".getBytes(StandardCharsets.UTF_8));
	}

	@AfterAll
	static void stop() throws IOException {
		SecureBaseObject.setDefaultTrustManagers(savedTrust);
		if( server != null ) {
			server.stop();
		}
	}

	@AfterEach
	void disconnectAll() throws IOException {
		for (FileSourceFactory f : made) {
			f.disConnect();
		}
		made.clear();
		SecureBaseObject.setDefaultTrustManagers(savedTrust);
	}

	private VfsWebdavsFileSourceFactory factory(String host) {
		VfsWebdavsFileSourceFactory f = new VfsWebdavsFileSourceFactory();
		f.setLogLevel(LogLevel.ERROR);
		Properties p = server.properties();
		p.setProperty(VfsWebFileSourceFactory.PROP_HOST, host);
		f.setConnectionProperties(p);
		made.add(f);
		return f;
	}

	@Test
	void aTrustedCertificateForTheHost() throws IOException {
		SecureBaseObject.setDefaultTrustManagers(trustTheTestCertificate);
		VfsWebdavsFileSourceFactory f = factory("localhost");
		assertTrue(f.connect());
		try (InputStream in = f.createFileSource("/a.txt").getInputStream()) {
			assertEquals("secure", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		FileSource w = f.createFileSource("/written.txt");
		try (OutputStream out = w.getOutputStream()) {
			out.write("over TLS".getBytes(StandardCharsets.UTF_8));
		}
		assertEquals("over TLS", new String(Files.readAllBytes(server.home().resolve("written.txt")), StandardCharsets.UTF_8));
		assertTrue(w.delete());
	}

	@Test
	void aCertificateTheJvmDoesntTrustIsRefused() {
		VfsWebdavsFileSourceFactory f = factory("localhost");
		assertThrows(IOException.class, f::connect);
		assertFalse(f.isConnected());
	}

	@Test
	void aTrustedCertificateForAnotherHostIsRefused() {
		SecureBaseObject.setDefaultTrustManagers(trustTheTestCertificate);
		VfsWebdavsFileSourceFactory f = factory(VfsWebdavTestServer.HOST);
		assertThrows(IOException.class, f::connect, "the certificate is for localhost only");
		assertFalse(f.isConnected());
	}
}
