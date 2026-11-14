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
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import us.bringardner.parley.files.test.FileSourceTestSupport;

/**
 * An SFTP server for one test class: Apache MINA SSHD on the loopback address, on a free port,
 * serving a new directory of its own as "/" to one user, who logs in with the password or with a
 * key given to {@link #authorize}. Start it, use it, stop it (the shared suites stop the one in
 * {@code FileSourceTestSupport.server} after the class).
 */
final class VfsSftpTestServer implements VfsTestServer {

	static final String USER = "unittest1";
	static final String PASSWORD = "0000";
	static final String HOST = "127.0.0.1";

	private static final AtomicInteger COUNT = new AtomicInteger();

	private final Path home;
	private final KeyPair hostKey;
	private SshServer sshd;
	/** The public keys USER may log in with, as SSH key blobs in Base64. */
	private final Set<String> authorizedKeys = ConcurrentHashMap.newKeySet();

	VfsSftpTestServer() throws IOException {
		home = Paths.get(FileSourceTestSupport.scratchDir("vfs-sftp-" + COUNT.incrementAndGet()));
		Files.createDirectories(home);
		try {
			KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
			g.initialize(new ECGenParameterSpec("secp256r1"));
			hostKey = g.generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new IOException("Can't make a host key: " + e, e);
		}
	}

	/** A started server. */
	static VfsSftpTestServer started() throws IOException {
		VfsSftpTestServer s = new VfsSftpTestServer();
		FileSourceTestSupport.startServer(s, 10_000);
		return s;
	}

	@Override
	public synchronized void start() throws IOException {
		SshServer s = SshServer.setUpDefaultServer();
		s.setHost(InetAddress.getLoopbackAddress().getHostAddress());
		s.setPort(0);
		s.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
		s.setPasswordAuthenticator((u, p, session) -> USER.equals(u) && PASSWORD.equals(p));
		s.setPublickeyAuthenticator((u, k, session) -> USER.equals(u) && authorizedKeys.contains(blob(k)));
		s.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
		s.setFileSystemFactory(new VirtualFileSystemFactory(home));
		s.start();
		sshd = s;
	}

	@Override
	public synchronized void stop() throws IOException {
		SshServer s = sshd;
		sshd = null;
		if( s != null ) {
			s.stop(true);
		}
	}

	@Override
	public synchronized boolean isRunning() {
		return sshd != null && sshd.isStarted();
	}

	@Override
	public String getName() {
		return "MINA SFTP test server";
	}

	synchronized int port() {
		return sshd.getPort();
	}

	@Override
	public Path home() {
		return home;
	}

	/** Lets USER log in with this key. */
	void authorize(PublicKey key) {
		authorizedKeys.add(blob(key));
	}

	/** Lets USER log in with the key in this SSH public key blob (as an ssh-agent lists it). */
	void authorize(byte[] sshPublicKeyBlob) {
		authorizedKeys.add(Base64.getEncoder().encodeToString(sshPublicKeyBlob));
	}

	/** The key as an SSH public key blob (no length before it), in Base64. */
	private static String blob(PublicKey key) {
		ByteArrayBuffer b = new ByteArrayBuffer();
		b.putRawPublicKey(key);
		return Base64.getEncoder().encodeToString(b.getCompactData());
	}

	/** A known_hosts line for this server, as OpenSSH writes one for a port other than 22. */
	String knownHostsLine() {
		return "[" + HOST + "]:" + port() + " " + PublicKeyEntry.toString(hostKey.getPublic());
	}

	/** Settings for USER on this server, without host key checking (the host key tests check it). */
	Properties properties() {
		Properties p = new Properties();
		p.setProperty(VfsSftpFileSourceFactory.PROP_HOST, HOST);
		p.setProperty(VfsSftpFileSourceFactory.PROP_PORT, "" + port());
		p.setProperty(VfsSftpFileSourceFactory.PROP_USER, USER);
		p.setProperty(VfsSftpFileSourceFactory.PROP_PASSWORD, PASSWORD);
		p.setProperty(VfsSftpFileSourceFactory.PROP_STRICT_HOST_KEY_CHECKING, "no");
		p.setProperty(VfsSftpFileSourceFactory.PROP_CONNECT_TIMEOUT, "10s");
		return p;
	}

	/** A factory connected as USER; the caller disconnects it. */
	@Override
	public VfsSftpFileSourceFactory connect() throws IOException {
		VfsSftpFileSourceFactory f = new VfsSftpFileSourceFactory();
		f.setConnectionProperties(properties());
		assertTrue(f.connect(), "can't connect to " + HOST + ":" + port());
		return f;
	}
}
