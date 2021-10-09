/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.nukkitx.network.raknet.proxy;

import com.nukkitx.network.raknet.proxy.HAProxyProxiedProtocol.AddressFamily;
import io.netty.buffer.ByteBuf;
import io.netty.util.ByteProcessor;
import io.netty.util.CharsetUtil;
import io.netty.util.NetUtil;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * Message container for decoded HAProxy proxy protocol parameters
 */
public final class HAProxyMessage {
    private final HAProxyProtocolVersion protocolVersion;
    private final HAProxyCommand command;
    private final HAProxyProxiedProtocol proxiedProtocol;
    private final String sourceAddress;
    private final String destinationAddress;
    private final int sourcePort;
    private final int destinationPort;

    /**
     * Creates a new instance
     */
    private HAProxyMessage(
            HAProxyProtocolVersion protocolVersion, HAProxyCommand command, HAProxyProxiedProtocol proxiedProtocol,
            String sourceAddress, String destinationAddress, String sourcePort, String destinationPort) {
        this(
                protocolVersion, command, proxiedProtocol,
                sourceAddress, destinationAddress, portStringToInt(sourcePort), portStringToInt(destinationPort));
    }

    /**
     * Creates a new instance of HAProxyMessage.
     * @param protocolVersion the protocol version.
     * @param command the command.
     * @param proxiedProtocol the protocol containing the address family and transport protocol.
     * @param sourceAddress the source address.
     * @param destinationAddress the destination address.
     * @param sourcePort the source port. This value must be 0 for unix, unspec addresses.
     * @param destinationPort the destination port. This value must be 0 for unix, unspec addresses.
     */
    public HAProxyMessage(
            HAProxyProtocolVersion protocolVersion, HAProxyCommand command, HAProxyProxiedProtocol proxiedProtocol,
            String sourceAddress, String destinationAddress, int sourcePort, int destinationPort) {

        ObjectUtil.checkNotNull(protocolVersion, "protocolVersion");
        ObjectUtil.checkNotNull(proxiedProtocol, "proxiedProtocol");
        AddressFamily addrFamily = proxiedProtocol.addressFamily();

        checkAddress(sourceAddress, addrFamily);
        checkAddress(destinationAddress, addrFamily);
        checkPort(sourcePort, addrFamily);
        checkPort(destinationPort, addrFamily);

        this.protocolVersion = protocolVersion;
        this.command = command;
        this.proxiedProtocol = proxiedProtocol;
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
    }

    /**
     * Decodes a version 2, binary proxy protocol header.
     *
     * @param header                     a version 2 proxy protocol header
     * @return                           {@link HAProxyMessage} instance
     * @throws HAProxyProtocolException  if any portion of the header is invalid
     */
    static HAProxyMessage decodeHeader(ByteBuf header) {
        ObjectUtil.checkNotNull(header, "header");

        if (header.readableBytes() < 16) {
            throw new HAProxyProtocolException(
                    "incomplete header: " + header.readableBytes() + " bytes (expected: 16+ bytes)");
        }

        // Per spec, the 13th byte is the protocol version and command byte
        header.skipBytes(12);
        final byte verCmdByte = header.readByte();

        HAProxyProtocolVersion ver;
        try {
            ver = HAProxyProtocolVersion.valueOf(verCmdByte);
        } catch (IllegalArgumentException e) {
            throw new HAProxyProtocolException(e);
        }

        if (ver != HAProxyProtocolVersion.V2) {
            throw new HAProxyProtocolException("version 1 unsupported: 0x" + Integer.toHexString(verCmdByte));
        }

        HAProxyCommand cmd;
        try {
            cmd = HAProxyCommand.valueOf(verCmdByte);
        } catch (IllegalArgumentException e) {
            throw new HAProxyProtocolException(e);
        }

        if (cmd == HAProxyCommand.LOCAL) {
            return unknownMsg(HAProxyProtocolVersion.V2, HAProxyCommand.LOCAL);
        }

        // Per spec, the 14th byte is the protocol and address family byte
        HAProxyProxiedProtocol protAndFam;
        try {
            protAndFam = HAProxyProxiedProtocol.valueOf(header.readByte());
        } catch (IllegalArgumentException e) {
            throw new HAProxyProtocolException(e);
        }

        if (protAndFam == HAProxyProxiedProtocol.UNKNOWN) {
            return unknownMsg(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY);
        }

        int addressInfoLen = header.readUnsignedShort();

        String srcAddress;
        String dstAddress;
        int addressLen;
        int srcPort = 0;
        int dstPort = 0;

        AddressFamily addressFamily = protAndFam.addressFamily();

        if (addressFamily == AddressFamily.AF_UNIX) {
            // unix sockets require 216 bytes for address information
            if (addressInfoLen < 216 || header.readableBytes() < 216) {
                throw new HAProxyProtocolException(
                    "incomplete UNIX socket address information: " +
                            Math.min(addressInfoLen, header.readableBytes()) + " bytes (expected: 216+ bytes)");
            }
            int startIdx = header.readerIndex();
            int addressEnd = header.forEachByte(startIdx, 108, ByteProcessor.FIND_NUL);
            if (addressEnd == -1) {
                addressLen = 108;
            } else {
                addressLen = addressEnd - startIdx;
            }
            srcAddress = header.toString(startIdx, addressLen, CharsetUtil.US_ASCII);

            startIdx += 108;

            addressEnd = header.forEachByte(startIdx, 108, ByteProcessor.FIND_NUL);
            if (addressEnd == -1) {
                addressLen = 108;
            } else {
                addressLen = addressEnd - startIdx;
            }
            dstAddress = header.toString(startIdx, addressLen, CharsetUtil.US_ASCII);
            // AF_UNIX defines that exactly 108 bytes are reserved for the address. The previous methods
            // did not increase the reader index although we already consumed the information.
            header.readerIndex(startIdx + 108);
        } else {
            if (addressFamily == AddressFamily.AF_IPv4) {
                // IPv4 requires 12 bytes for address information
                if (addressInfoLen < 12 || header.readableBytes() < 12) {
                    throw new HAProxyProtocolException(
                        "incomplete IPv4 address information: " +
                                Math.min(addressInfoLen, header.readableBytes()) + " bytes (expected: 12+ bytes)");
                }
                addressLen = 4;
            } else if (addressFamily == AddressFamily.AF_IPv6) {
                // IPv6 requires 36 bytes for address information
                if (addressInfoLen < 36 || header.readableBytes() < 36) {
                    throw new HAProxyProtocolException(
                        "incomplete IPv6 address information: " +
                                Math.min(addressInfoLen, header.readableBytes()) + " bytes (expected: 36+ bytes)");
                }
                addressLen = 16;
            } else {
                throw new HAProxyProtocolException(
                    "unable to parse address information (unknown address family: " + addressFamily + ')');
            }

            // Per spec, the src address begins at the 17th byte
            srcAddress = ipBytesToString(header, addressLen);
            dstAddress = ipBytesToString(header, addressLen);
            srcPort = header.readUnsignedShort();
            dstPort = header.readUnsignedShort();
        }

        while (skipNextTLV(header)) {

        }
        return new HAProxyMessage(ver, cmd, protAndFam, srcAddress, dstAddress, srcPort, dstPort);
    }

    private static boolean skipNextTLV(final ByteBuf header) {
        // We need at least 4 bytes for a TLV
        if (header.readableBytes() < 4) {
            return false;
        }

        header.skipBytes(1);
        header.skipBytes(header.readUnsignedShort());
        return true;
    }

    /**
     * Decodes a version 1, human-readable proxy protocol header.
     *
     * @param header                     a version 1 proxy protocol header
     * @return                           {@link HAProxyMessage} instance
     * @throws HAProxyProtocolException  if any portion of the header is invalid
     */
    static HAProxyMessage decodeHeader(String header) {
        if (header == null) {
            throw new HAProxyProtocolException("header");
        }

        String[] parts = header.split(" ");
        int numParts = parts.length;

        if (numParts < 2) {
            throw new HAProxyProtocolException(
                    "invalid header: " + header + " (expected: 'PROXY' and proxied protocol values)");
        }

        if (!"PROXY".equals(parts[0])) {
            throw new HAProxyProtocolException("unknown identifier: " + parts[0]);
        }

        HAProxyProxiedProtocol protAndFam;
        try {
            protAndFam = HAProxyProxiedProtocol.valueOf(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new HAProxyProtocolException(e);
        }

        if (protAndFam != HAProxyProxiedProtocol.TCP4 &&
                protAndFam != HAProxyProxiedProtocol.TCP6 &&
                protAndFam != HAProxyProxiedProtocol.UNKNOWN) {
            throw new HAProxyProtocolException("unsupported v1 proxied protocol: " + parts[1]);
        }

        if (protAndFam == HAProxyProxiedProtocol.UNKNOWN) {
            return unknownMsg(HAProxyProtocolVersion.V1, HAProxyCommand.PROXY);
        }

        if (numParts != 6) {
            throw new HAProxyProtocolException("invalid TCP4/6 header: " + header + " (expected: 6 parts)");
        }

        try {
            return new HAProxyMessage(
                    HAProxyProtocolVersion.V1, HAProxyCommand.PROXY,
                    protAndFam, parts[2], parts[3], parts[4], parts[5]);
        } catch (RuntimeException e) {
            throw new HAProxyProtocolException("invalid HAProxy message", e);
        }
    }

    /**
     * Proxy protocol message for 'UNKNOWN' proxied protocols. Per spec, when the proxied protocol is
     * 'UNKNOWN' we must discard all other header values.
     */
    private static HAProxyMessage unknownMsg(HAProxyProtocolVersion version, HAProxyCommand command) {
        return new HAProxyMessage(version, command, HAProxyProxiedProtocol.UNKNOWN, null, null, 0, 0);
    }

    /**
     * Convert ip address bytes to string representation
     *
     * @param header     buffer containing ip address bytes
     * @param addressLen number of bytes to read (4 bytes for IPv4, 16 bytes for IPv6)
     * @return           string representation of the ip address
     */
    private static String ipBytesToString(ByteBuf header, int addressLen) {
        StringBuilder sb = new StringBuilder();
        final int ipv4Len = 4;
        final int ipv6Len = 8;
        if (addressLen == ipv4Len) {
            for (int i = 0; i < ipv4Len; i++) {
                sb.append(header.readByte() & 0xff);
                sb.append('.');
            }
        } else {
            for (int i = 0; i < ipv6Len; i++) {
                sb.append(Integer.toHexString(header.readUnsignedShort()));
                sb.append(':');
            }
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /**
     * Convert port to integer
     *
     * @param value                      the port
     * @return                           port as an integer
     * @throws IllegalArgumentException  if port is not a valid integer
     */
    private static int portStringToInt(String value) {
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + value, e);
        }

        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("invalid port: " + value + " (expected: 1 ~ 65535)");
        }

        return port;
    }

    /**
     * Validate an address (IPv4, IPv6, Unix Socket)
     *
     * @param address                    human-readable address
     * @param addrFamily                 the {@link AddressFamily} to check the address against
     * @throws IllegalArgumentException  if the address is invalid
     */
    private static void checkAddress(String address, AddressFamily addrFamily) {
        ObjectUtil.checkNotNull(addrFamily, "addrFamily");

        switch (addrFamily) {
            case AF_UNSPEC:
                if (address != null) {
                    throw new IllegalArgumentException("unable to validate an AF_UNSPEC address: " + address);
                }
                return;
            case AF_UNIX:
                ObjectUtil.checkNotNull(address, "address");
                if (address.getBytes(CharsetUtil.US_ASCII).length > 108) {
                    throw new IllegalArgumentException("invalid AF_UNIX address: " + address);
                }
                return;
        }

        ObjectUtil.checkNotNull(address, "address");

        switch (addrFamily) {
            case AF_IPv4:
                if (!NetUtil.isValidIpV4Address(address)) {
                    throw new IllegalArgumentException("invalid IPv4 address: " + address);
                }
                break;
            case AF_IPv6:
                if (!NetUtil.isValidIpV6Address(address)) {
                    throw new IllegalArgumentException("invalid IPv6 address: " + address);
                }
                break;
            default:
                throw new IllegalArgumentException("unexpected addrFamily: " + addrFamily);
        }
    }

    /**
     * Validate the port depending on the addrFamily.
     *
     * @param port                       the UDP/TCP port
     * @throws IllegalArgumentException  if the port is out of range (0-65535 inclusive)
     */
    private static void checkPort(int port, AddressFamily addrFamily) {
        switch (addrFamily) {
        case AF_IPv6:
        case AF_IPv4:
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("invalid port: " + port + " (expected: 0 ~ 65535)");
            }
            break;
        case AF_UNIX:
        case AF_UNSPEC:
            if (port != 0) {
                throw new IllegalArgumentException("port cannot be specified with addrFamily: " + addrFamily);
            }
            break;
        default:
            throw new IllegalArgumentException("unexpected addrFamily: " + addrFamily);
        }
    }

    /**
     * Returns the {@link HAProxyProtocolVersion} of this {@link HAProxyMessage}.
     */
    public HAProxyProtocolVersion protocolVersion() {
        return protocolVersion;
    }

    /**
     * Returns the {@link HAProxyCommand} of this {@link HAProxyMessage}.
     */
    public HAProxyCommand command() {
        return command;
    }

    /**
     * Returns the {@link HAProxyProxiedProtocol} of this {@link HAProxyMessage}.
     */
    public HAProxyProxiedProtocol proxiedProtocol() {
        return proxiedProtocol;
    }

    /**
     * Returns the human-readable source address of this {@link HAProxyMessage}.
     */
    public String sourceAddress() {
        return sourceAddress;
    }

    /**
     * Returns the human-readable destination address of this {@link HAProxyMessage}.
     */
    public String destinationAddress() {
        return destinationAddress;
    }

    /**
     * Returns the UDP/TCP source port of this {@link HAProxyMessage}.
     */
    public int sourcePort() {
        return sourcePort;
    }

    /**
     * Returns the UDP/TCP destination port of this {@link HAProxyMessage}.
     */
    public int destinationPort() {
        return destinationPort;
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(this) +
                "(protocolVersion: " + protocolVersion +
                ", command: " + command +
                ", proxiedProtocol: " + proxiedProtocol +
                ", sourceAddress: " + sourceAddress +
                ", destinationAddress: " + destinationAddress +
                ", sourcePort: " + sourcePort +
                ", destinationPort: " + destinationPort +
                ")";
    }

    public InetSocketAddress sourceInetSocketAddress() {
        AddressFamily af = proxiedProtocol.addressFamily();
        if (af != AddressFamily.AF_IPv4 && af!= AddressFamily.AF_IPv6) {
            throw new IllegalStateException("Unsupported address family: " + proxiedProtocol.addressFamily());
        }
        InetAddress addr;
        try {
            addr = Inet4Address.getByName(sourceAddress);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
        return new InetSocketAddress(addr, sourcePort);
    }
}