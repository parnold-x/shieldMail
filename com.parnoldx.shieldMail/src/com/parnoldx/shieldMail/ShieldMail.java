package com.parnoldx.shieldMail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import javax.mail.Address;
import javax.mail.Folder;
import javax.mail.FolderClosedException;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.event.MessageCountEvent;
import javax.mail.event.MessageCountListener;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.fluffypeople.managesieve.ManageSieveClient;
import com.fluffypeople.managesieve.ManageSieveResponse;
import com.fluffypeople.managesieve.ParseException;
import com.fluffypeople.managesieve.SieveScript;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

public class ShieldMail {
	private static Properties properties;
	private Map<String, List<String>> data = new HashMap<>();
	private String sieveContent;
	private String sieveName;

	// settings
	private static final String timeoutDefault = "50000";
	// name
	private static final String SHIELD_MAIL = "ShieldMail";
	// props
	private static final String TIMEOUT = "mail.imap.timeout";
	private static final String FOLDERS = "imap.folders";
	private static final String SIEVE_PORT = "sieve.port";
	private static final String PASSWORD = "mail.imap.password";
	private static final String USER = "mail.imap.user";
	private static final String IMAP_PORT = "mail.imap.port";
	private static final String HOST = "mail.imap.host";
	private static final String MAIL_STORE_PROTOCOL = "mail.store.protocol";

	public static void main(String[] args) throws Exception {
		Path propertiesFile = Path.of("properties.xml");
		if (!Files.exists(propertiesFile)) {
			Properties properties = new Properties();
			properties.put(HOST, "yourhost");
			properties.put(USER, "username");
			properties.put(PASSWORD, "password");
			properties.put(IMAP_PORT, "993");
			properties.put(SIEVE_PORT, "4190");
			properties.put(MAIL_STORE_PROTOCOL, "imaps");
			properties.put("mail.imap.starttls.enable", "true");
			properties.put(FOLDERS, "[]");
			properties.storeToXML(Files.newOutputStream(propertiesFile), null);
			System.out.println("Configure your IMAP setting in the properties.xml file");
			return;
		}
		properties = new Properties();
		properties.loadFromXML(Files.newInputStream(propertiesFile));
		properties.putIfAbsent(TIMEOUT, timeoutDefault);
		System.out.println("Configuration loaded");
		System.out.println(properties.getProperty(USER) + " for server " + properties.getProperty(HOST) + ":"
			+ properties.getProperty(IMAP_PORT));
		// connect to imap server and start handling
		new ShieldMail().start(properties);
	}

	private void start(Properties properties)
		throws MessagingException, InterruptedException, NumberFormatException, IOException, ParseException {
		while (true) {
			try {
				Session emailSession = Session.getDefaultInstance(properties);
				IMAPStore imapStore = (IMAPStore) emailSession
					.getStore(properties.getProperty(MAIL_STORE_PROTOCOL, "imaps"));
				imapStore.connect(properties.getProperty(HOST), Integer.parseInt(properties.getProperty(IMAP_PORT)),
					properties.getProperty(USER), properties.getProperty(PASSWORD));
				List<String> folders = getFolders(properties);
				final IMAPFolder inbox = (IMAPFolder) imapStore.getFolder("Inbox");
				if (folders.isEmpty()) {
					for (Folder folder : inbox.list()) {
						watchFolder(folder);
						folders.add(folder.getFullName());
					}
				} else {
					Iterator<String> iterator = folders.iterator();
					while (iterator.hasNext()) {
						String f = iterator.next();
						try {
							Folder folder = imapStore.getFolder(f);
							watchFolder(folder);
						} catch (MessagingException e) {
							System.err.println("Could not find folder " + f);
							iterator.remove();
						}
					}
				}
				if (folders.isEmpty()) {
					System.out.println("No folder to watch");
					return;
				}
				handleActiveSieveScript();
				System.out.println("Connected successfully. Watching:");
				for (String f : folders) {
					System.out.println(" " + f);
				}
				for (;;) {
					Thread.sleep(100000);
				}
			} catch (Exception e) {
				System.out.println("Reconnect");
			}
		}
	}

	private void watchFolder(Folder folder) throws MessagingException {
		data.put(folder.getFullName(), new ArrayList<>());
		new Thread(() -> {
			try {
				idle((IMAPFolder) folder);
			} catch (MessagingException | InterruptedException e) {
				throw new IllegalStateException(e);
			}
		}).start();

	}

	void idle(IMAPFolder folder) throws FolderClosedException, MessagingException, InterruptedException {
		// We need to create a new thread to keep alive the connection
		new Thread(new KeepAliveRunnable(folder, Integer.parseInt(properties.getProperty(TIMEOUT)) - 1),
			"IdleConnectionKeepAlive").start();

		folder.addMessageCountListener(new MessageCountListener() {

			@Override
			public void messagesRemoved(MessageCountEvent arg0) {
			}

			@Override
			public void messagesAdded(MessageCountEvent e) {
				for (Message m : e.getMessages()) {
					try {
						for (Address ad : m.getFrom()) {
							handleAddress(ad, folder);
						}
					} catch (MessagingException | IOException | ParseException e1) {
						throw new IllegalStateException(e1);
					}
				}
			}
		});
		for (;;) {
			if (!folder.isOpen()) {
				folder.open(IMAPFolder.READ_ONLY);
			}
			folder.idle();
		}
	}

	protected void handleAddress(Address ad, Folder folder) throws IOException, ParseException {
		String address = ad.toString();
		if (address.contains("<")) {
			address = address.substring(address.indexOf("<") + 1, address.indexOf(">"));
		}
		address = "\"" + address + "\"";
		List<String> list = data.get(folder.getFullName());
		if (list == null) {
			throw new IllegalStateException();
		}
		if (list.contains(address)) {
			return;
		}
		System.out.println(" Add " + address + " to sieve " + sieveName);
		list.add(address);
		ManageSieveClient client = connectSieve(properties);
		ManageSieveResponse putscript = client.putscript(sieveName, getNewSieveScript());
		client.logout();
		if (!putscript.isOk()) {
			throw new IllegalStateException(putscript.getMessage());
		}
	}

	private String getNewSieveScript() {
		StringBuilder sb = new StringBuilder(sieveContent);
		int id = 100;
		for (Entry<String, List<String>> entry : data.entrySet()) {
			if (entry.getValue().isEmpty()) {
				continue;
			}
			sb.append("## Flag: |UniqueId:");
			sb.append(id++);
			sb.append("|Rulename: ");
			sb.append(SHIELD_MAIL);
			sb.append(" ");
			sb.append(getLastElementName(entry.getKey()));
			sb.append("\n");
			sb.append("if header :contains \"From\" [");
			sb.append(String.join(",", entry.getValue()));
			sb.append("]\n{\n");
			sb.append("addflag \"\\\\seen\" ;\n");
			sb.append("fileinto \"");
			sb.append(entry.getKey());
			sb.append("\";\n}\n");
		}
		return sb.toString();
	}

	private String getLastElementName(String key) {
		if (!key.contains("/")) {
			return key;
		}
		String[] split = key.split("/");
		return split[split.length - 1];
	}

	private void handleActiveSieveScript() throws IOException, ParseException {
		ManageSieveClient client = connectSieve(properties);
		SieveScript script = getActiveScripts(client);
		if (script == null) {
			// could be also handled here by code but no time for that
			throw new IllegalStateException(
				"No active sieve script found. Please configure one with all requires etc.");
		}

		ManageSieveResponse resp = client.getScript(script);
		if (!resp.isOk()) {
			throw new IOException("Could not get body of script [" + script.getName() + "]: " + resp.getMessage());
		}
		String body = script.getBody();
		Path backup = Path.of("backup.sieve");
		if (!Files.exists(backup)) {
			Files.write(backup, body.getBytes());
		}
		List<String> asList = new ArrayList<String>(Arrays.asList(body.split("\n")));
		// TODO improve
		int armed = -1;
		List<String> mails = null;
		for (int i = 0; i < asList.size(); i++) {
			String line = asList.get(i);
			if (line.startsWith("##") && line.contains(SHIELD_MAIL)) {
				armed = i;
			} else if (armed > 0 && line.startsWith("if")) {
				mails = new ArrayList<>();
				String adList = line.substring(line.indexOf("[") + 1, line.indexOf("]"));
				String[] adListSplit = adList.split(",");
				for (String ad : adListSplit) {
					mails.add(ad);
				}
			} else if (armed > 0 && line.startsWith("fileinto")) {
				String folder = line.substring(line.indexOf("\"") + 1, line.lastIndexOf("\""));
				List<String> list = data.get(folder);
				if (list == null) {
					throw new IllegalStateException();
				}
				list.addAll(mails);
			} else if (armed > 0 && line.startsWith("}")) {
				for (int j = 0; j <= (i - armed); j++) {
					asList.remove(armed);
				}
				i = armed;
				mails = null;
				armed = -1;
			}
		}
		sieveContent = String.join("\n", asList);
		if (!sieveContent.endsWith("\n")) {
			sieveContent = sieveContent + "\n";
		}
		sieveName = script.getName();
		client.logout();
	}

	private SieveScript getActiveScripts(ManageSieveClient client) throws IOException, ParseException {
		List<SieveScript> scripts = new ArrayList<SieveScript>();

		// Get the list of this users scripts. The current contents of
		// the list will be deleted first.
		ManageSieveResponse resp = client.listscripts(scripts);
		if (!resp.isOk()) {
			throw new IOException("Could not get list of scripts: " + resp.getMessage());
		}
		for (SieveScript sieveScript : scripts) {
			if (sieveScript.isActive()) {
				return sieveScript;
			}
		}
		return null;
	}

	ManageSieveClient connectSieve(Properties properties) throws IOException, ParseException {
		ManageSieveClient client = new ManageSieveClient();

		ManageSieveResponse resp = client.connect(properties.getProperty(HOST),
			Integer.parseInt(properties.getProperty(SIEVE_PORT)));

		if (!resp.isOk()) {
			throw new IOException("Can't connect to sieve server: " + resp.getMessage());
		}
		resp = client.starttls(getInsecureSSLFactory(), false);
		if (!resp.isOk()) {
			throw new IOException("Can't start SSL:" + resp.getMessage());
		}
		// Authenticate the easy way. If your server does something complicated,
		// look at the other version of authenticate.
		resp = client.authenticate(properties.getProperty(USER), properties.getProperty(PASSWORD));
		if (!resp.isOk()) {
			throw new IOException("Could not authenticate: " + resp.getMessage());
		}
		return client;
	}

	private static List<String> getFolders(Properties properties) {
		String property = properties.getProperty(FOLDERS, "[]");
		if (property.trim().length() == 0) {
			return new ArrayList<>();
		}
		property = property.substring(1, property.length() - 1);
		if (property.trim().length() == 0) {
			return new ArrayList<>();
		}
		String[] split = property.split(",");
		ArrayList<String> folders = new ArrayList<>();
		for (String string : split) {
			folders.add(string);
		}
		return folders;
	}

	/**
	 * Create a SSLSocketFactory that ignores Certificate Validation. You are
	 * strongly advised not to use this in production code. (Partly because the
	 *
	 * @return a non-validating SSLSocketFactory
	 */
	private static SSLSocketFactory getInsecureSSLFactory() {
		try {
			// Create a trust manager that does not validate certificate chains
			TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
				@Override
				public X509Certificate[] getAcceptedIssuers() {
					return new X509Certificate[0];
				}

				@Override
				public void checkClientTrusted(X509Certificate[] certs, String authType) {
				}

				@Override
				public void checkServerTrusted(X509Certificate[] certs, String authType) {
				}
			} };

			SSLContext sc = SSLContext.getInstance("SSL");
			sc.init(null, trustAllCerts, new SecureRandom());
			return sc.getSocketFactory();
		} catch (NoSuchAlgorithmException ex) {
			return null;
		} catch (KeyManagementException ex) {
			return null;
		}
	}

	private static class KeepAliveRunnable implements Runnable {

		private IMAPFolder folder;
		private int waitTime;

		public KeepAliveRunnable(IMAPFolder folder, int waitTime) {
			this.folder = folder;
			this.waitTime = waitTime;
		}

		@Override
		public void run() {
			while (!Thread.interrupted()) {
				try {
					Thread.sleep(waitTime);
					folder.isOpen();
				} catch (InterruptedException e) {
				}
			}
		}
	}
}
