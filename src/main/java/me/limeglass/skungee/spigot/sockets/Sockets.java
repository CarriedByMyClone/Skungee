package me.limeglass.skungee.spigot.sockets;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import me.limeglass.skungee.EncryptionUtil;
import me.limeglass.skungee.UniversalSkungee;
import me.limeglass.skungee.objects.SkungeePacket;
import me.limeglass.skungee.objects.SkungeePacketType;
import me.limeglass.skungee.objects.SkungeePlayer;
import me.limeglass.skungee.spigot.Skungee;

public class Sockets {

	public static Map<InetAddress, Integer> attempts = new HashMap<InetAddress, Integer>();
	public static Set<InetAddress> blocked = new HashSet<InetAddress>();
	public static Socket bungeecord;
	private static Boolean restart = true, checking = false, isConnected = false;
	private static int task, heartbeat, keepAlive;
	public static Long lastSent = System.currentTimeMillis();
	//private static EncryptionUtil encrypter = Skungee.getEncrypter();
	
	//TODO create a system to cache failed packets, It already does but it gives up after a few times and lets it go.
	
	@SuppressWarnings("deprecation")
	private static void startHeartbeat() {
		task = Bukkit.getScheduler().scheduleAsyncRepeatingTask(Skungee.getInstance(), new Runnable() {
			@Override
			public void run() {
				Boolean answer = (Boolean) send(new SkungeePacket(true, SkungeePacketType.HEARTBEAT, Bukkit.getPort()));
				if (answer != null && answer) {
					stop(true);
				}
			}
		}, 1, Skungee.getInstance().getConfig().getInt("heartbeat", 30));
	}
	
	@SuppressWarnings("deprecation")
	private static void keepAlive() {
		restart = true;
		keepAlive = Bukkit.getScheduler().scheduleAsyncRepeatingTask(Skungee.getInstance(), new Runnable() {
			@SuppressWarnings("resource")
			@Override
			public void run() {
				try {
					new Socket(Skungee.getInstance().getConfig().getString("host", "0.0.0.0"), Skungee.getInstance().getConfig().getInt("port", 1337));
					Bukkit.getScheduler().cancelTask(keepAlive);
					Skungee.consoleMessage("Connection established again!");
					connect();
				} catch (IOException e) {}
			}
		}, 1, Skungee.getInstance().getConfig().getInt("keepAlive", 10) * 20);
	}
	
	public static void connect() {
		Set<SkungeePlayer> whitelisted = new HashSet<SkungeePlayer>();
		for (OfflinePlayer player : Bukkit.getWhitelistedPlayers()) {
			whitelisted.add(new SkungeePlayer(true, player.getUniqueId(), player.getName()));
		}
		ArrayList<Object> data = new ArrayList<Object>(Arrays.asList(Skungee.getInstance().getConfig().getBoolean("Reciever.enabled", false), Reciever.getReciever().getLocalPort(), Bukkit.getPort(), whitelisted, Skungee.getInstance().getConfig().getInt("heartbeat", 30) * 60, Bukkit.getMotd(), Bukkit.getMaxPlayers()));
		Bukkit.getScheduler().runTaskAsynchronously(Skungee.getInstance(), new Runnable() {
			@Override
			public void run() {
				if (getSocketConnection() == null) {
					stop(false);
					restart = true;
				} else {
					for (int i = 0; i < 10; i++) {
						String state = (String) send(new SkungeePacket(true, SkungeePacketType.PING, data));
						if (state != null && state.equals("CONNECTED")) {
							isConnected = true;
							Skungee.consoleMessage("Successfully connected to the Bungeecord Skungee.");
							break;
						}
						Skungee.debugMessage("Ping packet had no response, configurion for the connection to Bungeecord Skungee may not be valid or blocked. Attempting to try again... " + (i + 1) + "/10");
					}
					startHeartbeat();
				}
			}
		});
	}

	private static Socket getSocketConnection() {
		for (int i = 0; i < Skungee.getInstance().getConfig().getInt("maxAttempts", 20); i++) {
			try {
				return new Socket(Skungee.getInstance().getConfig().getString("host", "0.0.0.0"), Skungee.getInstance().getConfig().getInt("port", 1337));
			} catch (IOException e) {}
		}
		Skungee.consoleMessage("Could not establish connection to Skungee on the Bungeecord!");
		return null;
	}

	public static Object send(SkungeePacket packet) {
		if (packet.isReturnable()) return (isConnected) ? send_i(packet) : (packet.getType() == SkungeePacketType.PING) ? send_i(packet) : null;
		if (Skungee.getInstance().getConfig().getBoolean("Queue.enabled", true)) {
			PacketQueue.queue(packet);
		} else {
			Bukkit.getScheduler().runTaskAsynchronously(Skungee.getInstance(), new Runnable() {
				@Override
				public void run() {
					send_i(packet);
				}
			});
		}
		return null;
	}
	
	public static Object send_i(SkungeePacket packet) {
		try {
			if (!checking) {
				checking = true;
				bungeecord = getSocketConnection();
				checking = false;
				if (bungeecord == null) {
					Bukkit.getScheduler().cancelTask(heartbeat);
					stop(restart);
					restart = false;
				} else {
					EncryptionUtil encrypter = new EncryptionUtil(Skungee.getInstance(), true);
					if (!Skungee.getInstance().getConfig().getBoolean("IgnoreSpamPackets", true)) {
						Skungee.debugMessage("Sending " + UniversalSkungee.getPacketDebug(packet));
					} else if (!(packet.getType() == SkungeePacketType.HEARTBEAT)) {
						Skungee.debugMessage("Sending " + UniversalSkungee.getPacketDebug(packet));
					}
					if (Skungee.getInstance().getConfig().getBoolean("security.password.enabled", false)) {
						byte[] password = encrypter.serialize(Skungee.getInstance().getConfig().getString("security.password.password"));
						if (Skungee.getInstance().getConfig().getBoolean("security.password.hash", true)) {
							if (Skungee.getInstance().getConfig().getBoolean("security.password.hashFile", false) && encrypter.isFileHashed()) {
								password = encrypter.getHashFromFile();
							} else {
								password = encrypter.hash();
							}
						}
						if (password != null) packet.setPassword(password);
					}
					ObjectOutputStream objectOutputStream = new ObjectOutputStream(bungeecord.getOutputStream());
					//TODO Add cipher encryption + change config message.
					if (Skungee.getInstance().getConfig().getBoolean("security.encryption.enabled", false)) {
						byte[] serialized = encrypter.serialize(packet);
						objectOutputStream.writeObject(Base64.getEncoder().encode(serialized));
					} else {
						objectOutputStream.writeObject(packet);
					}
					lastSent = System.currentTimeMillis();
					bungeecord.setSoTimeout(10000);
					ObjectInputStream objectInputStream = new ObjectInputStream(bungeecord.getInputStream());
					if (packet.isReturnable()) {
						//TODO Add cipher encryption + change config message.
						if (Skungee.getInstance().getConfig().getBoolean("security.encryption.enabled", false)) {
							byte[] decoded = Base64.getDecoder().decode((byte[]) objectInputStream.readObject());
							return encrypter.deserialize(decoded);
						} else {
							return objectInputStream.readObject();
						}
					}
					objectOutputStream.close();
					objectInputStream.close();
					bungeecord.close();
				}
			}
		} catch (ClassNotFoundException | IOException e) {}
		return null;
	}
	
	public static void onPluginDisabling() {
		Bukkit.getScheduler().cancelTask(task);
		Bukkit.getScheduler().cancelTask(heartbeat);
		Bukkit.getScheduler().cancelTask(keepAlive);
		if (bungeecord != null) {
			try {
				bungeecord.close();
			} catch (IOException e) {
				Skungee.exception(e, "&cError closing main socket.");
			}
		}
	}
	
	//Stops everything with the option to restart.
	public static void stop(Boolean reconnect) {
		Bukkit.getScheduler().cancelTask(task);
		Bukkit.getScheduler().cancelTask(heartbeat);
		Bukkit.getScheduler().cancelTask(keepAlive);
		if (bungeecord != null) {
			try {
				bungeecord.close();
			} catch (IOException e) {
				Skungee.exception(e, "&cError closing main socket.");
			}
		}
		isConnected = false;
		if (reconnect) {
			Skungee.consoleMessage("&6Attempting to reconnect to Skungee...");
			connect();
		} else if (Skungee.getInstance().getConfig().getBoolean("reconnect", false)) {
			Skungee.consoleMessage("&6Going into keep alive mode...");
			keepAlive();
		} else {
			Skungee.consoleMessage("&cDisconnected from Skungee!");
			Skungee.consoleMessage("Could be incorrect Skungee details, there was no socket found or was denied access. For socket at " + Skungee.getInstance().getConfig().getString("host", "0.0.0.0") + ":" + Skungee.getInstance().getConfig().getInt("port", 1337));
		}
	}
}