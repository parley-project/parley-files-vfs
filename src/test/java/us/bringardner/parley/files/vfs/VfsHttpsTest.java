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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.core.SecureBaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * vfshttps and the certificate check VFS's http provider doesn't make (docs/DESIGN.md, 16.1): a
 * certificate the JVM (or the application) doesn't trust is refused, as is a trusted one for
 * another host, also when an http URL redirects to https; VFS used directly accepts both. The test
 * certificate is for "localhost" only, trusted as parley-files-viewer's certificate dialog would
 * after asking (SecureBaseObject's default trust managers).
 */
public class VfsHttpsTest {

	private static final String PASSWORD = "test-only";

	private static TrustManager[] savedTrust;
	private static TrustManager[] trustTheTestCertificate;
	private static VfsHttpTestServer tls;
	private static VfsHttpTestServer plain;

	private final List<FileSourceFactory> made = new ArrayList<>();

	@BeforeAll
	static void start() throws Exception {
		Path dir = Files.createTempDirectory("parley-files-vfs-https");
		Path ks = VfsFtpTlsTest.keyStore(dir, "server", "CN=localhost", "dns:localhost");
		KeyStore k = KeyStore.getInstance("PKCS12");
		try (InputStream in = new FileInputStream(ks.toFile())) {
			k.load(in, PASSWORD.toCharArray());
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(k, PASSWORD.toCharArray());
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(kmf.getKeyManagers(), null, null);

		KeyStore trust = KeyStore.getInstance("PKCS12");
		trust.load(null, null);
		trust.setCertificateEntry("server", VfsFtpTlsTest.certificate(ks));
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(trust);
		trustTheTestCertificate = tmf.getTrustManagers();
		savedTrust = SecureBaseObject.getDefaultTrustManagers();

		tls = VfsHttpTestServer.started(ctx);
		Files.write(tls.home().resolve("a.txt"), "secure".getBytes(StandardCharsets.UTF_8));
		Files.createDirectories(tls.home().resolve("dir"));
		Files.write(tls.home().resolve("dir").resolve("b.txt"), "b".getBytes(StandardCharsets.UTF_8));
		plain = VfsHttpTestServer.started(null);
		plain.redirect("/to-https", "https://localhost:" + tls.port() + "/a.txt");
	}

	@AfterAll
	static void stop() {
		SecureBaseObject.setDefaultTrustManagers(savedTrust);
		if( tls != null ) {
			tls.stop();
		}
		if( plain != null ) {
			plain.stop();
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

	private <T extends VfsHttpFileSourceFactory> T factory(T f, VfsHttpTestServer s, String host) {
		f.setLogLevel(LogLevel.ERROR);
		Properties p = s.properties();
		p.setProperty(VfsHttpFileSourceFactory.PROP_HOST, host);
		f.setConnectionProperties(p);
		made.add(f);
		return f;
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return VfsHttpTestServer.text(in);
		}
	}

	@Test
	void aTrustedCertificateForTheHost() throws IOException {
		SecureBaseObject.setDefaultTrustManagers(trustTheTestCertificate);
		VfsHttpsFileSourceFactory f = factory(new VfsHttpsFileSourceFactory(), tls, "localhost");
		assertTrue(f.connect());
		assertEquals("secure", read(f.createFileSource("/a.txt")));
		assertTrue(f.createFileSource("/dir").isDirectory(), "the folder check goes over TLS too");
		assertEquals("b", read(f.createFileSource("/dir").listFiles()[0]));
		assertEquals("vfshttps://localhost:" + tls.port() + "/", f.getURL());
	}

	/** VFS's own default trusts any certificate: here the JVM's trust store decides. */
	@Test
	void aCertificateTheJvmDoesntTrustIsRefused() {
		VfsHttpsFileSourceFactory f = factory(new VfsHttpsFileSourceFactory(), tls, "localhost");
		assertThrows(IOException.class, f::connect);
		assertFalse(f.isConnected());
	}

	@Test
	void aTrustedCertificateForAnotherHostIsRefused() {
		SecureBaseObject.setDefaultTrustManagers(trustTheTestCertificate);
		VfsHttpsFileSourceFactory f = factory(new VfsHttpsFileSourceFactory(), tls, VfsHttpTestServer.HOST);
		assertThrows(IOException.class, f::connect, "the certificate is for localhost only");
		assertFalse(f.isConnected());
	}

	/** An http URL that redirects to https is checked the same way (both of VFS's providers are replaced). */
	@Test
	void aRedirectToHttpsIsChecked() throws IOException {
		VfsHttpFileSourceFactory f = factory(new VfsHttpFileSourceFactory(), plain, VfsHttpTestServer.HOST);
		assertTrue(f.connect());
		FileSource to = f.createFileSource("/to-https");
		assertThrows(IOException.class, () -> read(to), "untrusted after the redirect");
		SecureBaseObject.setDefaultTrustManagers(trustTheTestCertificate);
		VfsHttpFileSourceFactory trusted = factory(new VfsHttpFileSourceFactory(), plain, VfsHttpTestServer.HOST);
		assertTrue(trusted.connect());
		assertEquals("secure", read(trusted.createFileSource("/to-https")), "trusted, and for the host redirected to");
	}

	/** What vfshttps fixes: VFS used directly reads from a server whose certificate nothing trusts. */
	@Test
	void vfsAloneTrustsAnything() throws IOException {
		StandardFileSystemManager m2 = new StandardFileSystemManager();
		try {
			m2.init();
			FileObject f = m2.resolveFile("https://localhost:" + tls.port() + "/a.txt");
			try (InputStream in = f.getContent().getInputStream()) {
				assertEquals("secure", VfsHttpTestServer.text(in), "a certificate nothing trusts, accepted");
			}
		} finally {
			m2.close();
		}
	}
}
