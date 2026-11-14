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
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.vfs2.FileSystemOptions;

import us.bringardner.parley.files.ConnectionSetting;

/**
 * Hadoop's HDFS through VFS's HDFS provider (type id {@value #FACTORY_ID}), read and write. Add
 * {@code org.apache.commons:commons-vfs2-hdfs} and Hadoop's client
 * ({@code org.apache.hadoop:hadoop-client}): VFS's pom drops every dependency of Hadoop's, so its
 * HDFS provider can't run on its own.
 * <p>
 * Settings: the name node's {@value #PROP_HOST} and {@value #PROP_PORT} (8020), and optionally a
 * Hadoop configuration file ({@value #PROP_CONFIG}, such as {@code hdfs-site.xml}) for what a
 * cluster needs beyond its address. The user is Hadoop's, as for its own client (the local user,
 * or HADOOP_USER_NAME). Paths are the cluster's. URL {@code vfshdfs://namenode:8020/user/tony/a.txt}.
 * <p>
 * This module doesn't compile against VFS's HDFS provider or Hadoop (a large library): the one
 * option it sets, the configuration file, is set through reflection (docs/DESIGN.md, 18). Its tests
 * run only when a cluster is given.
 */
public class VfsHdfsFileSourceFactory extends VfsFileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = TYPE_ID_PREFIX + "hdfs";

	public static final String PROP_HOST = "host";
	public static final String PROP_PORT = "port";
	/** A local Hadoop configuration file (hdfs-site.xml, core-site.xml), optional. */
	public static final String PROP_CONFIG = "config";
	/** The name node's RPC port. */
	public static final int DEFAULT_PORT = 8020;

	private static final String CONFIG_BUILDER = "org.apache.commons.vfs2.provider.hdfs.HdfsFileSystemConfigBuilder";

	private volatile String host;
	private volatile int port = -1;
	private volatile String config;

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host == null || host.trim().isEmpty() ? null : host.trim();
	}

	public int getPort() {
		return port <= 0 ? DEFAULT_PORT : port;
	}

	/** @param port the port; 0 or less means {@value #DEFAULT_PORT} */
	public void setPort(int port) {
		this.port = port <= 0 ? -1 : port;
	}

	/** @return the Hadoop configuration file, or null */
	public String getConfig() {
		return config;
	}

	/** @param config a local Hadoop configuration file; empty or null for none */
	public void setConfig(String config) {
		this.config = config == null || config.trim().isEmpty() ? null : config.trim();
	}

	/** VFS's HDFS provider and Hadoop's file system: VFS drops the scheme without either. */
	@Override
	protected String libraryClass() {
		return "org.apache.commons.vfs2.provider.hdfs.HdfsFileProvider, org.apache.hadoop.fs.FileSystem";
	}

	@Override
	protected String libraryName() {
		return "commons-vfs2-hdfs (org.apache.commons:commons-vfs2-hdfs) and Hadoop's client (org.apache.hadoop:hadoop-client)";
	}

	/** {@code hdfs://namenode:8020/}. */
	@Override
	protected String rootUri() throws IOException {
		if( host == null ) {
			throw new IOException("No host for " + getTypeId());
		}
		String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
		return "hdfs://" + h.toLowerCase(Locale.ROOT) + ":" + getPort() + "/";
	}

	/** The configuration file, given to VFS's HDFS options by reflection (no compile-time Hadoop). */
	@Override
	protected void configure(FileSystemOptions options) throws IOException {
		if( config == null ) {
			return;
		}
		Path p = Paths.get(config);
		if( !p.toFile().isFile() ) {
			throw new IOException(getTypeId() + ": the Hadoop configuration file " + config + " isn't there");
		}
		try {
			Class<?> b = Class.forName(CONFIG_BUILDER, true, getClass().getClassLoader());
			Object builder = b.getMethod("getInstance").invoke(null);
			Method set = b.getMethod("setConfigURL", FileSystemOptions.class, URL.class);
			set.invoke(builder, options, p.toUri().toURL());
		} catch (ReflectiveOperationException | LinkageError e) {
			throw new IOException(getTypeId() + " can't set its Hadoop configuration (" + CONFIG_BUILDER + "): " + e, e);
		}
	}

	/**
	 * The root, asked for once: a name node that can't be reached fails the connect with the reason
	 * (VFS's message is "Unknown message with code \"Unable to check existence \"").
	 */
	@Override
	protected org.apache.commons.vfs2.FileObject resolveRoot(org.apache.commons.vfs2.impl.StandardFileSystemManager m,
			FileSystemOptions options) throws IOException {
		org.apache.commons.vfs2.FileObject ret = super.resolveRoot(m, options);
		try {
			ret.exists();
		} catch (IOException e) {
			Throwable root = e;
			while( root.getCause() != null && root.getCause() != root ) {
				root = root.getCause();
			}
			throw new IOException(getURL() + ": " + (root == e ? e.getMessage() : root.toString()), e);
		}
		return ret;
	}

	@Override
	protected Map<String, String> urlSettings() {
		return config == null ? Collections.emptyMap() : Collections.singletonMap(PROP_CONFIG, config);
	}

	@Override
	public List<ConnectionSetting> getConnectionSettings() {
		return List.of(ConnectionSetting.text(PROP_HOST, "Name node").asRequired(),
				ConnectionSetting.integer(PROP_PORT, "Port", 1L, 65535L).withDefault("" + DEFAULT_PORT).asRequired(),
				ConnectionSetting.localFile(PROP_CONFIG, "Hadoop configuration").asAdvanced()
						.withDescription("A Hadoop configuration file (hdfs-site.xml, core-site.xml), for what the cluster needs beyond its address"));
	}

	@Override
	public Properties getConnectProperties() {
		Properties ret = new Properties();
		ret.setProperty(PROP_HOST, host == null ? "" : host);
		ret.setProperty(PROP_PORT, "" + getPort());
		ret.setProperty(PROP_CONFIG, config == null ? "" : config);
		return ret;
	}

	/** Only the settings present in P change; an empty value clears one. */
	@Override
	public void setConnectionProperties(Properties p) {
		if( p.containsKey(PROP_HOST) ) {
			setHost(p.getProperty(PROP_HOST));
		}
		String portValue = p.getProperty(PROP_PORT);
		if( portValue != null ) {
			setPort(portValue.trim().isEmpty() ? -1 : Integer.parseInt(portValue.trim()));
		}
		if( p.containsKey(PROP_CONFIG) ) {
			setConfig(p.getProperty(PROP_CONFIG));
		}
	}
}
