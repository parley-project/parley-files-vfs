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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.ftps.FtpsDataChannelProtectionLevel;
import org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.apache.commons.vfs2.provider.ftps.FtpsMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.SecureBaseObject;
import us.bringardner.parley.files.FileSource;

/**
 * vfsftp with secure (explicit TLS: AUTH TLS) and vfsftps (implicit TLS) against Parley's
 * FtpServer, and the certificate check VFS doesn't make by itself (DESIGN 10): a certificate the
 * JVM doesn't trust is refused, as is one for another host. The test certificate is for
 * "localhost" only, and is trusted as parley-files-viewer's certificate dialog would after asking
 * (SecureBaseObject's default trust managers).
 */
public class VfsFtpTlsTest {

	private static final String[] KEYSTORE_PROPERTIES = {
			"parley.ftp.keyStoreName", "parley.ftp.keyStorePassword", "parley.ftp.keyStoreType", "parley.ftp.algorithm" };
	private static final String PASSWORD = "test-only";

	private static final List<String> savedProperties = new ArrayList<>();
	private static TrustManager[] savedTrust;
	private static TrustManager[] trustTheTestCertificate;
	private static Path dir;
	private static VfsFtpTestServer explicitServer;
	private static VfsFtpTestServer implicitServer;

	/** A key pair and self-signed certificate made with the JDK's keytool. */
	static Path keyStore(Path dir, String name, String dname, String san) throws Exception {
		Path ks = dir.resolve(name + ".p12");
		List<String> cmd = new ArrayList<>(List.of(Paths.get(System.getProperty("java.home"), "bin", "keytool").toString(),
				"-genkeypair", "-noprompt", "-alias", "server", "-dname", dname,
				"-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD,
				"-keyalg", "RSA", "-keysize", "2048", "-validity", "2"));
		if( san != null ) {
			cmd.add("-ext");
			cmd.add("SAN=" + san);
		}
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		p.getInputStream().readAllBytes();
		assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, "keytool failed");
		return ks;
	}

	static X509Certificate certificate(Path ks) throws Exception {
		KeyStore k = KeyStore.getInstance("PKCS12");
		try (InputStream in = new FileInputStream(ks.toFile())) {
			k.load(in, PASSWORD.toCharArray());
		}
		return (X509Certificate) k.getCertificate("server");
	}

	@BeforeAll
	static void start() throws Exception {
		dir = Files.createTempDirectory("parley-files-vfs-tls");
		Path ks = keyStore(dir, "server", "CN=localhost", "dns:localhost");

		KeyStore trust = KeyStore.getInstance("PKCS12");
		trust.load(null, null);
		trust.setCertificateEntry("server", certificate(ks));
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(trust);
		trustTheTestCertificate = tmf.getTrustManagers();

		for (String name : KEYSTORE_PROPERTIES) {
			savedProperties.add(System.getProperty(name));
		}
		System.setProperty("parley.ftp.keyStoreName", ks.toString());
		System.setProperty("parley.ftp.keyStorePassword", PASSWORD);
		System.setProperty("parley.ftp.keyStoreType", "PKCS12");
		System.setProperty("parley.ftp.algorithm", "SunX509");
		savedTrust = SecureBaseObject.getDefaultTrustManagers();
		SecureBaseObject.setDefaultTrustManagers(trustTheTestCertificate);

		explicitServer = VfsFtpTestServer.started(false);
		implicitServer = VfsFtpTestServer.started(true);
		for (VfsFtpTestServer s : new VfsFtpTestServer[] { explicitServer, implicitServer }) {
			Path folder = s.home().resolve("folder");
			Files.createDirectories(folder);
			Files.writeString(folder.resolve("a.txt"), "hello");
		}
	}

	@AfterAll
	static void stop() throws Exception {
		try {
			for (VfsFtpTestServer s : new VfsFtpTestServer[] { explicitServer, implicitServer }) {
				if( s != null ) {
					s.stop();
				}
			}
		} finally {
			explicitServer = null;
			implicitServer = null;
			SecureBaseObject.setDefaultTrustManagers(savedTrust);
			for (int i = 0; i < KEYSTORE_PROPERTIES.length && i < savedProperties.size(); i++) {
				if( savedProperties.get(i) == null ) {
					System.clearProperty(KEYSTORE_PROPERTIES[i]);
				} else {
					System.setProperty(KEYSTORE_PROPERTIES[i], savedProperties.get(i));
				}
			}
			savedProperties.clear();
			if( dir != null ) {
				try (var files = Files.list(dir)) {
					for (Path f : (Iterable<Path>) files::iterator) {
						Files.deleteIfExists(f);
					}
				}
				Files.deleteIfExists(dir);
			}
		}
	}

	/** Every message in E's chain of causes. */
	private static String causes(Throwable e) {
		StringBuilder b = new StringBuilder();
		for (Throwable t = e; t != null; t = t.getCause()) {
			b.append(t).append(" / ");
		}
		return b.toString();
	}

	private static Properties login(VfsFtpTestServer s, String host) {
		Properties p = s.properties();
		p.setProperty(VfsFtpFileSourceFactory.PROP_HOST, host);
		return p;
	}

	/** Lists /folder, reads a.txt and writes and reads back a file, so data connections are tried both ways. */
	private static void useIt(VfsFtpFileSourceFactory f) throws IOException {
		assertTrue(f.connect());
		FileSource folder = f.createFileSource("/folder");
		assertEquals(1, folder.listFiles().length);
		try (InputStream in = f.createFileSource("/folder/a.txt").getInputStream()) {
			assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		FileSource b = folder.getChild("b.txt");
		try (OutputStream out = b.getOutputStream()) {
			out.write("written over TLS".getBytes(StandardCharsets.UTF_8));
		}
		try (InputStream in = b.getInputStream()) {
			assertEquals("written over TLS", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		assertTrue(b.delete());
	}

	@Test
	void vfsftpWithSecureIsExplicitTls() throws IOException {
		VfsFtpFileSourceFactory f = new VfsFtpFileSourceFactory();
		Properties p = login(explicitServer, "localhost");
		p.setProperty(VfsFtpFileSourceFactory.PROP_SECURE, "true");
		f.setConnectionProperties(p);
		try {
			useIt(f);
		} finally {
			f.disConnect();
		}
	}

	@Test
	void vfsftpsIsImplicitTls() throws IOException {
		VfsFtpFileSourceFactory f = new VfsFtpsFileSourceFactory();
		f.setConnectionProperties(login(implicitServer, "localhost"));
		try {
			useIt(f);
			assertTrue(f.createFileSource("/folder").toURL().toString().contains("=vfsftps"));
		} finally {
			f.disConnect();
		}
	}

	/** VFS's own default trusts any certificate that is in date: here the JVM's trust store decides. */
	@Test
	void aCertificateTheJvmDoesntTrustIsRefused() throws IOException {
		SecureBaseObject.setDefaultTrustManagers(null);
		VfsFtpFileSourceFactory f = new VfsFtpsFileSourceFactory();
		try {
			f.setConnectionProperties(login(implicitServer, "localhost"));
			IOException e = assertThrows(IOException.class, f::connect);
			assertTrue(causes(e).contains("PKIX"), causes(e));
			assertFalse(f.isConnected());
		} finally {
			SecureBaseObject.setDefaultTrustManagers(trustTheTestCertificate);
			f.disConnect();
		}
	}

	/** A trusted certificate for another host is refused too (commons-net checks no host name). */
	@Test
	void aCertificateForAnotherHostIsRefused() throws IOException {
		for (VfsFtpFileSourceFactory f : new VfsFtpFileSourceFactory[] { new VfsFtpsFileSourceFactory(), new VfsFtpFileSourceFactory() }) {
			Properties p = login(f instanceof VfsFtpsFileSourceFactory ? implicitServer : explicitServer, "127.0.0.1");
			p.setProperty(VfsFtpFileSourceFactory.PROP_SECURE, "true");
			f.setConnectionProperties(p);
			try {
				IOException e = assertThrows(IOException.class, f::connect, f.getTypeId() + ": the certificate is for localhost only");
				assertTrue(causes(e).contains("isn't for 127.0.0.1"), causes(e));
				assertFalse(f.isConnected());
			} finally {
				f.disConnect();
			}
		}
	}

	/** Data connections protected (PROT P; VFS sends none unless told), the mode, and Parley's check. */
	@Test
	void tlsOptions() throws IOException {
		FtpsFileSystemConfigBuilder b = FtpsFileSystemConfigBuilder.getInstance();
		VfsFtpFileSourceFactory explicit = new VfsFtpFileSourceFactory();
		explicit.setConnectionProperties(login(explicitServer, "localhost"));
		explicit.setSecure(true);
		FileSystemOptions o = new FileSystemOptions();
		explicit.configure(o);
		assertEquals(FtpsMode.EXPLICIT, b.getFtpsMode(o));
		assertEquals(FtpsDataChannelProtectionLevel.P, b.getDataChannelProtectionLevel(o));
		assertTrue(b.getTrustManager(o) instanceof VfsTrustManager);
		assertEquals(Boolean.TRUE, b.getPassiveMode(o));

		VfsFtpFileSourceFactory implicit = new VfsFtpsFileSourceFactory();
		implicit.setConnectionProperties(login(implicitServer, "localhost"));
		FileSystemOptions io = new FileSystemOptions();
		implicit.configure(io);
		assertEquals(FtpsMode.IMPLICIT, b.getFtpsMode(io));
		assertEquals(FtpsDataChannelProtectionLevel.P, b.getDataChannelProtectionLevel(io));
	}
}
