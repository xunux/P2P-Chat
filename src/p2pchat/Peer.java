/*
 * P2PChat - Peer-to-Peer Chat Application
 *
 * Copyright (c) 2014 Ahmed Samy  <f.fallen45@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package p2pchat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import java.net.InetAddress;
import java.net.Socket;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Date;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import netlib.NetEventListener;
import netlib.Connection;
import netlib.PeerInfo;
import netlib.Server;

public class Peer implements NetEventListener
{
	private final Server server;
	private SocketChannel channel;	/* To identify other peers, not main.  */

	public String peerName;
	private int port;

	private final List children = new LinkedList();
	private final List connections = new LinkedList();

	private boolean awaitingPong = false;
	private Date timeSinceLastPing = null;
	private Runnable pingThread = null;

	public Peer(Peer peer)
	{
		server = null;

		if (peer != null)
			children.add(peer);
	}

	public Peer(Peer peer, String nick, String host, int port) throws IOException
	{
		peerName = nick;
		this.port   = port;

		if (peer != null)
			children.add(peer);

		server = new Server("".equals(host) ? null : InetAddress.getByName(host), port, this);
		new Thread(this.server).start();
	}

	public void connect(String host, int port) throws IOException
	{
		Connection conn = new Connection(InetAddress.getByName(host), port, this);
		new Thread(conn).start();
		connections.add(conn);
	}

	public boolean publishSelf(String host, int port)
	{
		try {
			try (Socket s = new Socket(host, port)) {
				DataOutputStream out = new DataOutputStream(s.getOutputStream());

				out.writeByte(0x1B);
				out.writeInt(this.port);

				s.close();
				return true;
			}
		} catch (IOException e) {
			e.printStackTrace();
			P2PChat.get().centralConnectionFailed();
		}

		return false;
	}

	/*
	 * The following function attempts to get a list
	 * of available peers from the central point.  See
	 * HybridCentralPoint.java for more information.
	 *
	 * If this function fails to connect or find any peers,
	 * a null is returned.
	*/
	public List discoverPeers(String host, int port)
	{
		try {
			try (Socket s = new Socket(host, port)) {
				DataOutputStream out = new DataOutputStream(s.getOutputStream());
				out.writeByte(0x1A);

				DataInputStream in = new DataInputStream(s.getInputStream());
				int nr_peers = in.readInt();
				if (nr_peers <= 0)
					return null;

				List peers = new LinkedList();
				for (int i = 0; i < nr_peers; ++i) {
					byte[] peerAddress = new byte[4];
					in.read(peerAddress);
					
					String peerHost = InetAddress.getByAddress(peerAddress).getHostName();
					int peerPort = in.readInt();

					if (peerHost.equals(server.getAddress().getHostName()))
						continue;

					PeerInfo peerInfo = new PeerInfo();
					peerInfo.port = peerPort;
					peerInfo.host = peerHost;
					
					peers.add(peerInfo);
				}

				return peers;
			}
		} catch (IOException e) {
			P2PChat.get().centralConnectionFailed();
		}

		return null;
	}

	public void kick(String name)
	{
		Iterator it = children.iterator();
		while (it.hasNext()) {
			Peer peer = (Peer) it.next();
			if (name.equals(peer.peerName)) {
				P2PChat.get().peerDisconnected(peer);
				if (channel != peer.channel) {
					server.close(peer.channel);
				} else {
					Connection c = findConnection(peer.channel);
					if (c != null) {
						c.disconnect();
						P2PChat.get().appendText("Network", "You kicked yourself.");
					}
				}
			}
		}
	}

	public boolean disconnectFrom(String hostName)
	{
		// Check if we're connected to that host
		for (Object obj : connections) {
			Connection c = (Connection) obj;
			String peerHost = c.getChannel().socket().getInetAddress().getHostName();
			if (peerHost.equals(hostName)) {
				c.disconnect();
				return true;
			}
		}

		// Ok, we're not conencted to that peer, check if he's connected to us
		for (Object obj : children) {
			Peer peer = (Peer) obj;
			if (server.hasChannel(peer.channel)) {
				server.close(peer.channel);
				return true;
			}
		}

		return false;
	}

	public void setName(String name)
	{
		if (!name.equals(peerName))
			sendName(name);
	}

	public void sendMessage(String message)
	{
		int len = message.length();
		if (len == 0)
			return;

		send(null, mkbuffer((byte)0x1A, message, len).array());
	}

	public void sendMessage(String message, Peer peer)
	{
		int len = message.length();
		if (len == 0)
			return;

		send(peer, mkbuffer((byte)0x1A, message, len).array());
	}

	private void putString(ByteBuffer buffer, String str, int len)
	{
		buffer.putInt(len);
		for (int i = 0; i < len; ++i)
			buffer.putChar(str.charAt(i));
	}

	private String getString(ByteBuffer buffer)
	{
		int len = buffer.getInt();
		if (len == 0)
			return null;

		char[] data = new char[len];
		for (int i = 0; i < len; ++i)
			data[i] = buffer.getChar();

		return new String(data);
	}

	private ByteBuffer mkbuffer(byte request, String str, int len)
	{
		ByteBuffer out = ByteBuffer.allocate((len * 2) + 5);
		out.put(request);
		putString(out, str, len);

		return out;
	}

	private void sendName(String newName)
	{
		if (newName == null)
			newName = peerName;

		int len = newName.length();
		if (len == 0)
			return;

		ByteBuffer out = mkbuffer((byte)0x1B, newName, len);
		Iterator it = children.iterator();
		while (it.hasNext())
			sendName((Peer)it.next(), out);
		peerName = newName;
	}

	private void sendName(Peer peer, ByteBuffer out)
	{
		if (peer == null)
			return;

		send(peer, out.array());
	}

	private void sendPeers(Peer peer)
	{
		// What we basically do here is that we send every single peer
		// that is connected to us or we're connected to, to that peer.

		Connection conn = findConnection(peer.channel);
		for (Object obj : children) {
			Peer p = (Peer) obj;
			if (p.port != 0) {
				ByteBuffer buffer = ByteBuffer.allocate(4096);
				buffer.put((byte)0x1D);

				String hostName = peer.channel.socket().getInetAddress().getHostAddress();
				putString(buffer, hostName, hostName.length());
				buffer.putInt(peer.port);

				byte[] peerData = buffer.array();
				if (conn != null)
					conn.send(peerData);
				else if (server.hasChannel(peer.channel))
					server.send(peer.channel, peerData);
			}
		}
	}

	private void sendPort(Peer peer)
	{
		// I know this is kind of a waste, but we have to use ByteBuffer
		// to take care of the byte order.  Could of just packed the port
		// into a byte array.
		ByteBuffer buffer = ByteBuffer.allocate(5);
		buffer.put((byte)0x1C);
		buffer.putInt(port);

		Connection conn = findConnection(peer.channel);
		if (conn != null)
			conn.send(buffer.array());
		else if (server.hasChannel(peer.channel))
			server.send(peer.channel, buffer.array());
	}

	private void send(Peer peer, byte[] data)
	{
		for (Object obj : connections)
			((Connection) obj).send(data);

		if (peer == null) {
			for (Object o : children) {
				Peer p = (Peer) o;
				if (server.hasChannel(p.channel))
					server.send(p.channel, data);
			}
		} else if (server.hasChannel(peer.channel))
			server.send(peer.channel, data);
	}

	private Connection findConnection(SocketChannel ch)
	{
		for (Object obj : connections) {
			Connection c = (Connection) obj;
			if (c.getChannel() == ch)
				return c;
		}

		return null;
	}

	private Peer findPeer(SocketChannel ch)
	{
		for (Object obj : children) {
			Peer peer = (Peer) obj;
			if (peer.channel == ch)
				return peer;
		}

		return null;
	}

	public boolean handleWrite(SocketChannel ch, int count)
	{
		System.out.println("handleWrite(): Wrote " + count + " bytes.");
		return true;
	}

	public boolean handleRead(SocketChannel ch, ByteBuffer buffer, int count)
	{
		while (buffer.hasRemaining()) {
			byte request = buffer.get();

			switch (request) {
			case 0x1A: {	// message received
				String message = getString(buffer);
				String sender = null;

				Peer p = findPeer(ch);
				if (p != null)
					sender = p.peerName;
				P2PChat.get().appendText(sender, message);
				break;
			} case 0x1B: {	// nickname changed
				String name = getString(buffer);
				Peer peer = findPeer(ch);

				if (peer != null) {
					P2PChat.get().peerNameChanged(peer, peer.peerName, name);
					peer.peerName = name;
				}
				break;
			} case 0x1C: {	// Acknowledge port
				int port = buffer.getInt();
				Peer peer = findPeer(ch);
				if (peer != null)
					peer.port = port;
				break;
			} case 0x1D: {
				// A peer sending us another peer he's connected to.
				String hostName = getString(buffer);
				int port = buffer.getInt();

				if (hostName.equals(server.getAddress().getHostName()) && port == this.port)
					return true;

				P2PChat.get().peerFound(hostName, port);
				break;
			} case 0x1E: {	// PING
				byte[] data = new byte[1];
				data[0] = 0x1F;

				Connection c = findConnection(ch);
				if (c != null)
					c.send(data);
				else
					server.send(ch, data);
				break;
			} case 0x1F: {	// PONG
				Peer peer = findPeer(ch);
				if (peer != null && peer.awaitingPong)
					peer.awaitingPong = false;
				break;
			} default:
				break;
			}
		}

		return true;
	}

	public boolean handleConnection(final SocketChannel ch)
	{
		final Peer peer = new Peer(this);
		children.add(peer);

		peer.channel = ch;
		peer.port    = port;

		sendName(peer, mkbuffer((byte)0x1B, peerName, peerName.length()));
		sendPort(peer);
		sendPeers(peer);

		peer.pingThread = new Runnable() {
			@Override
			public void run() {
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
				}

				byte[] data = new byte[1];
				data[0] = 0x1E;

				Connection c = findConnection(ch);
				while ((c != null && c.isConnected()) || server.hasChannel(ch)) {
					if (peer.awaitingPong
						&& peer.timeSinceLastPing != null && new Date().after(peer.timeSinceLastPing)) {
						// Disconnected peer, purge
						P2PChat.get().peerDisconnected(peer);
						children.remove(peer);
						return;
					}

					if (c != null)
						c.send(data);
					else
						server.send(ch, data);

					peer.awaitingPong = true;
					peer.timeSinceLastPing = new Date();
					try {
						Thread.sleep(10000);
					} catch (InterruptedException e) {
					}
				}
			}
		};
		new Thread(peer.pingThread).start();

		P2PChat.get().peerConnected(peer);
		return true;
	}

	public boolean handleConnectionClose(SocketChannel ch)
	{
		Peer peer = findPeer(ch);
		if (peer != null && peer.channel == ch) {
			P2PChat.get().peerDisconnected(peer);
			children.remove(peer);
			peer.pingThread = null;
			return true;
		}

		P2PChat.get().appendText("Network", "Unable to find disconnected peer!");
		return true;
	}
}
